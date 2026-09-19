package io.kelta.runtime.registry;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe implementation of {@link CollectionRegistry} using copy-on-write semantics.
 * 
 * <p>This implementation provides:
 * <ul>
 *   <li><b>Lock-free reads:</b> Read operations ({@link #get(String)}, {@link #getAllCollectionNames()})
 *       do not acquire any locks and can proceed concurrently without blocking.</li>
 *   <li><b>Serialized writes:</b> Write operations ({@link #register(CollectionDefinition)}, 
 *       {@link #unregister(String)}) acquire a write lock to ensure atomic updates.</li>
 *   <li><b>Copy-on-write:</b> Each write creates a new immutable map, ensuring readers always
 *       see a consistent snapshot.</li>
 *   <li><b>Version tracking:</b> Collection versions are tracked and incremented on updates.</li>
 *   <li><b>Listener notifications:</b> Listeners are notified after the lock is released to
 *       prevent deadlocks and allow listeners to call back into the registry.</li>
 * </ul>
 * 
 * <p>The implementation uses a volatile reference to an immutable map for the collection store,
 * which provides visibility guarantees for concurrent reads. A {@link ReentrantReadWriteLock}
 * is used only for write operations to serialize updates.
 * 
 * <p>Listeners are stored in a {@link CopyOnWriteArrayList} which is thread-safe for iteration
 * and modification from multiple threads.
 * 
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Multiple threads can safely call any method concurrently.
 * 
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li>Read operations: O(1) average case, no locking</li>
 *   <li>Write operations: O(n) where n is the number of collections (due to map copy)</li>
 *   <li>Listener notification: O(m) where m is the number of listeners</li>
 * </ul>
 * 
 * @since 1.0.0
 * @see CollectionRegistry
 * @see CollectionChangeListener
 */
public class ConcurrentCollectionRegistry implements CollectionRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(ConcurrentCollectionRegistry.class);
    
    /**
     * Volatile reference to immutable map for copy-on-write semantics.
     * The volatile keyword ensures visibility of the reference across threads.
     * The immutable map ensures readers see a consistent snapshot.
     */
    private volatile Map<String, CollectionDefinition> collections = Map.of();
    
    /**
     * Lock for write operations only.
     * Read operations do not acquire this lock.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * Thread-safe list of listeners.
     * CopyOnWriteArrayList allows safe iteration during notification while
     * other threads may add/remove listeners.
     */
    private final CopyOnWriteArrayList<CollectionChangeListener> listeners = new CopyOnWriteArrayList<>();
    
    /**
     * Internal version counter for tracking registry-wide changes.
     * Incremented on every write operation.
     */
    private volatile long registryVersion = 0;
    
    /**
     * Creates a new empty concurrent collection registry.
     */
    public ConcurrentCollectionRegistry() {
        logger.debug("ConcurrentCollectionRegistry initialized");
    }
    
    @Override
    public void register(CollectionDefinition definition) {
        Objects.requireNonNull(definition, "definition cannot be null");

        String key = definition.registryKey();
        CollectionDefinition oldDefinition = null;
        CollectionDefinition registeredDefinition;

        lock.writeLock().lock();
        try {
            // Create a mutable copy of the current map
            Map<String, CollectionDefinition> newMap = new HashMap<>(collections);

            // Check if this is an update or new registration
            oldDefinition = newMap.get(key);

            if (oldDefinition != null) {
                // Update: increment version if not already incremented
                if (definition.version() <= oldDefinition.version()) {
                    registeredDefinition = definition.withIncrementedVersion();
                } else {
                    registeredDefinition = definition;
                }
                logger.debug("Updating collection '{}' (key='{}') from version {} to version {}",
                    definition.name(), key, oldDefinition.version(), registeredDefinition.version());
            } else {
                // New registration
                registeredDefinition = definition;
                logger.debug("Registering new collection '{}' (key='{}') with version {}",
                    definition.name(), key, registeredDefinition.version());
            }

            newMap.put(key, registeredDefinition);

            // Replace with immutable snapshot
            collections = Map.copyOf(newMap);
            registryVersion++;

        } finally {
            lock.writeLock().unlock();
        }

        // Notify listeners outside the lock to prevent deadlocks
        // and allow listeners to call back into the registry
        if (oldDefinition == null) {
            notifyRegistered(registeredDefinition);
        } else {
            notifyUpdated(oldDefinition, registeredDefinition);
        }
    }

    @Override
    public CollectionDefinition get(String collectionName) {
        // No lock needed - reading volatile reference to immutable map
        if (collectionName == null) {
            return null;
        }

        // If tenant context is set, try tenant-scoped key first
        String tenantId = TenantContext.get();
        if (tenantId != null && !tenantId.isBlank()) {
            String tenantKey = tenantId + ":" + collectionName;
            CollectionDefinition result = collections.get(tenantKey);
            if (result != null) {
                return result;
            }
        }

        // Fall back to the bare-name key. Per CollectionDefinition.registryKey(), only
        // definitions with a null tenantId are ever stored there -- which is meant to be
        // exclusively system collections (see registryKey() javadoc). A custom collection
        // must never be servable through this slot to a tenant that doesn't own it, so this
        // is a hard restriction, not a heuristic: a bare-name hit that isn't systemCollection()
        // is refused and logged rather than returned, however it got there.
        CollectionDefinition fallback = collections.get(collectionName);
        if (fallback == null) {
            return null;
        }
        if (fallback.systemCollection()) {
            return fallback;
        }
        // A direct hit on the caller's own full "tenantId:name" key is not a bare-name fallback
        // -- the definition is tenant-owned and the owner is the bound tenant. Serve it.
        if (fallback.tenantId() != null && fallback.tenantId().equals(tenantId)) {
            return fallback;
        }

        if (tenantId != null && !tenantId.isBlank()) {
            logger.warn("Refusing bare-name fallback: '{}' resolved to a custom collection owned by "
                    + "tenant '{}' while resolving for tenant '{}' -- this would have been a "
                    + "cross-tenant leak; returning null instead",
                    collectionName, fallback.tenantId(), tenantId);
        } else {
            logger.warn("Refusing bare-name fallback: '{}' resolved to a custom collection owned by "
                    + "tenant '{}' with no tenant context bound -- returning null instead",
                    collectionName, fallback.tenantId());
        }
        return null;
    }
    
    @Override
    public Set<String> getAllCollectionNames() {
        // No lock needed - the keySet of an immutable map is also immutable
        return collections.keySet();
    }

    @Override
    public List<CollectionDefinition> getAllForCurrentTenant() {
        // No lock needed - reading volatile reference to immutable map. Filters on the
        // definition's own tenantId rather than on key shape, so the result mirrors exactly
        // what get(name) would serve to this tenant: system collections plus its own.
        String tenantId = TenantContext.get();
        boolean tenantBound = tenantId != null && !tenantId.isBlank();
        List<CollectionDefinition> visible = new ArrayList<>();
        for (CollectionDefinition def : collections.values()) {
            if (def.systemCollection()) {
                visible.add(def);
            } else if (tenantBound && tenantId.equals(def.tenantId())) {
                visible.add(def);
            }
        }
        return List.copyOf(visible);
    }
    
    @Override
    public void unregister(String collectionName) {
        if (collectionName == null) {
            return;
        }

        boolean wasRemoved = false;

        lock.writeLock().lock();
        try {
            // Try direct key match first (handles both "name" and "tenantId:name" keys)
            if (collections.containsKey(collectionName)) {
                Map<String, CollectionDefinition> newMap = new HashMap<>(collections);
                newMap.remove(collectionName);
                collections = Map.copyOf(newMap);
                registryVersion++;
                wasRemoved = true;
                logger.debug("Unregistered collection '{}'", collectionName);
            } else {
                // Fall back: remove any entry whose collection name matches
                Map<String, CollectionDefinition> newMap = new HashMap<>(collections);
                String keyToRemove = null;
                for (Map.Entry<String, CollectionDefinition> entry : newMap.entrySet()) {
                    if (entry.getValue().name().equals(collectionName)) {
                        keyToRemove = entry.getKey();
                        break;
                    }
                }
                if (keyToRemove != null) {
                    newMap.remove(keyToRemove);
                    collections = Map.copyOf(newMap);
                    registryVersion++;
                    wasRemoved = true;
                    logger.debug("Unregistered collection '{}' (key='{}')", collectionName, keyToRemove);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Notify listeners outside the lock
        if (wasRemoved) {
            notifyUnregistered(collectionName);
        }
    }
    
    @Override
    public void addListener(CollectionChangeListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
        logger.debug("Added collection change listener: {}", listener.getClass().getSimpleName());
    }
    
    @Override
    public void removeListener(CollectionChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
            logger.debug("Removed collection change listener: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * Gets the current number of registered collections.
     * 
     * @return the number of collections in the registry
     */
    public int size() {
        return collections.size();
    }
    
    /**
     * Checks if the registry is empty.
     * 
     * @return true if no collections are registered, false otherwise
     */
    public boolean isEmpty() {
        return collections.isEmpty();
    }
    
    /**
     * Checks if a collection with the given name is registered.
     * 
     * @param collectionName the collection name to check
     * @return true if the collection is registered, false otherwise
     */
    public boolean contains(String collectionName) {
        if (collections.containsKey(collectionName)) {
            return true;
        }
        // Check if any tenant-scoped entry has this collection name
        for (CollectionDefinition def : collections.values()) {
            if (def.name().equals(collectionName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Gets the current registry version.
     * 
     * <p>The registry version is incremented on every write operation
     * (register or unregister). This can be used to detect changes
     * to the registry.
     * 
     * @return the current registry version
     */
    public long getRegistryVersion() {
        return registryVersion;
    }
    
    /**
     * Gets the number of registered listeners.
     * 
     * @return the number of listeners
     */
    public int getListenerCount() {
        return listeners.size();
    }
    
    /**
     * Notifies all listeners that a new collection was registered.
     * 
     * @param definition the newly registered collection
     */
    private void notifyRegistered(CollectionDefinition definition) {
        for (CollectionChangeListener listener : listeners) {
            try {
                listener.onCollectionRegistered(definition);
            } catch (Exception e) {
                logger.error("Error notifying listener of collection registration for '{}': {}", 
                    definition.name(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Notifies all listeners that a collection was updated.
     * 
     * @param oldDefinition the previous collection definition
     * @param newDefinition the new collection definition
     */
    private void notifyUpdated(CollectionDefinition oldDefinition, CollectionDefinition newDefinition) {
        for (CollectionChangeListener listener : listeners) {
            try {
                listener.onCollectionUpdated(oldDefinition, newDefinition);
            } catch (Exception e) {
                logger.error("Error notifying listener of collection update for '{}': {}", 
                    newDefinition.name(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Notifies all listeners that a collection was unregistered.
     * 
     * @param collectionName the name of the unregistered collection
     */
    private void notifyUnregistered(String collectionName) {
        for (CollectionChangeListener listener : listeners) {
            try {
                listener.onCollectionUnregistered(collectionName);
            } catch (Exception e) {
                logger.error("Error notifying listener of collection unregistration for '{}': {}", 
                    collectionName, e.getMessage(), e);
            }
        }
    }
}
