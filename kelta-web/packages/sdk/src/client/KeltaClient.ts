import type { AxiosInstance } from 'axios';
import axios from 'axios';
import type { KeltaClientConfig, CacheConfig, RetryConfig } from './types';
import type { ResourceMetadata } from '../types';
import { ResourceClient } from '../resources/ResourceClient';
import { AdminClient } from '../admin/AdminClient';
import { TokenManager } from '../auth/TokenManager';
import { DiscoveryResponseSchema } from '../validation/schemas';
import { ValidationError, mapAxiosError } from '../errors';

/**
 * Default cache configuration
 */
const DEFAULT_CACHE_CONFIG: Required<CacheConfig> = {
  discoveryTTL: 300000, // 5 minutes
};

/**
 * Default retry configuration
 */
const DEFAULT_RETRY_CONFIG: Required<RetryConfig> = {
  maxAttempts: 3,
  backoffMultiplier: 2,
  maxDelay: 10000,
};

/**
 * Main Kelta client for interacting with Kelta services
 */
export class KeltaClient {
  private readonly baseUrl: string;
  private readonly axiosInstance: AxiosInstance;
  private readonly tokenManager?: TokenManager;
  private readonly cacheConfig: Required<CacheConfig>;
  private readonly retryConfig: Required<RetryConfig>;
  private readonly validationEnabled: boolean;

  // Discovery cache
  private discoveryCache: ResourceMetadata[] | null = null;
  private discoveryCacheTime: number = 0;

  /**
   * Admin client for platform administration operations
   */
  public readonly admin: AdminClient;

  constructor(config: KeltaClientConfig) {
    const rawBase = config.baseUrl.replace(/\/$/, ''); // Remove trailing slash
    this.baseUrl = config.tenantSlug ? `${rawBase}/${config.tenantSlug}` : rawBase;
    this.cacheConfig = { ...DEFAULT_CACHE_CONFIG, ...config.cache };
    this.retryConfig = { ...DEFAULT_RETRY_CONFIG, ...config.retry };
    this.validationEnabled = config.validation ?? true;

    // Initialize Axios instance
    this.axiosInstance = axios.create({
      baseURL: this.baseUrl,
      headers: {
        'Content-Type': 'application/json',
      },
      ...config.axiosConfig,
    });

    // Initialize token manager if provider is configured
    if (config.tokenProvider) {
      this.tokenManager = new TokenManager(config.tokenProvider);
      this.setupAuthInterceptor();
    }

    // Trace interceptor must see the raw response/error before the retry interceptor
    // re-issues a request, so each actual HTTP attempt gets exactly one trace line.
    if (process.env.KELTA_TRACE === '1') {
      this.setupTraceInterceptor();
    }

    // Setup retry interceptor
    this.setupRetryInterceptor();

    // Initialize admin client
    this.admin = new AdminClient(this.axiosInstance);
  }

  /**
   * Get the base URL
   */
  getBaseUrl(): string {
    return this.baseUrl;
  }

  /**
   * Get the Axios instance (for internal use)
   */
  getAxiosInstance(): AxiosInstance {
    return this.axiosInstance;
  }

  /**
   * Check if validation is enabled
   */
  isValidationEnabled(): boolean {
    return this.validationEnabled;
  }

  /**
   * Discover available resources via the JSON:API collections endpoint.
   *
   * Fetches resource metadata from /api/collections (served by the
   * DynamicCollectionRouter) and caches the results for the configured
   * TTL period.
   *
   * Only user-facing (non-system) collections are returned by default.
   *
   * @returns Promise<ResourceMetadata[]> - Array of resource metadata objects
   * @throws ValidationError - If the response doesn't match the expected schema
   * @throws KeltaError - If the API request fails
   */
  async discover(): Promise<ResourceMetadata[]> {
    // Check cache - return cached data if within TTL
    const now = Date.now();
    if (this.discoveryCache && now - this.discoveryCacheTime < this.cacheConfig.discoveryTTL) {
      return this.discoveryCache;
    }

    try {
      // Fetch collections from JSON:API endpoint
      const response = await this.axiosInstance.get(
        '/api/collections?filter[systemCollection][eq]=false&page[size]=500'
      );

      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body = response.data;

      // Convert JSON:API response to ResourceMetadata[] format
      // JSON:API shape: { data: [{ type, id, attributes }] }
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access
      const dataArray: unknown[] = Array.isArray(body?.data) ? body.data : [];

      const resources: ResourceMetadata[] = dataArray.map((item: unknown) => {
        const resource = item as { id: string; attributes?: Record<string, unknown> };
        const attrs = resource.attributes ?? {};
        return {
          name: (attrs.name as string) ?? '',
          displayName: (attrs.displayName as string) ?? (attrs.name as string) ?? '',
          fields: [], // Fields are fetched separately per collection
          operations: ['list', 'get', 'create', 'update', 'delete'],
        };
      });

      // Validate with Zod schema if validation is enabled
      if (this.validationEnabled) {
        const parseResult = DiscoveryResponseSchema.safeParse({ resources });
        if (!parseResult.success) {
          const errorMessages = parseResult.error.errors.map(
            (e) => `${e.path.join('.')}: ${e.message}`
          );
          throw new ValidationError(`Invalid discovery response: ${errorMessages.join(', ')}`, {
            schema: errorMessages,
          });
        }
      }

      // Update cache with fresh data
      this.discoveryCache = resources;
      this.discoveryCacheTime = now;

      return resources;
    } catch (error) {
      // Re-throw Kelta errors as-is
      if (error instanceof ValidationError) {
        throw error;
      }
      // Map other errors to appropriate Kelta error types
      throw mapAxiosError(error);
    }
  }

  /**
   * Get a resource client for the specified resource
   */
  resource<T = unknown>(name: string): ResourceClient<T> {
    return new ResourceClient<T>(this, name);
  }

  /**
   * Build a full URL from an endpoint path
   */
  buildUrl(endpoint: string): string {
    const cleanEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
    return `${this.baseUrl}${cleanEndpoint}`;
  }

  /**
   * Clear the discovery cache
   */
  clearDiscoveryCache(): void {
    this.discoveryCache = null;
    this.discoveryCacheTime = 0;
  }

  /**
   * Setup authentication interceptor
   */
  private setupAuthInterceptor(): void {
    this.axiosInstance.interceptors.request.use(async (config) => {
      if (this.tokenManager) {
        const token = await this.tokenManager.getValidToken();
        if (token) {
          config.headers.Authorization = `Bearer ${token}`;
        }
      }
      return config;
    });
  }

  /**
   * Setup the KELTA_TRACE request tracer: one stderr line per actual HTTP attempt
   * (`method url status`), so a caller can count real network round trips — e.g. the
   * agent-authoring e2e benchmark's request budget — without instrumenting every command.
   */
  private setupTraceInterceptor(): void {
    this.axiosInstance.interceptors.response.use(
      (response) => {
        this.writeTrace(response.config?.method, response.config?.url, response.status);
        return response;
      },
      (error) => {
        if (axios.isAxiosError(error)) {
          this.writeTrace(error.config?.method, error.config?.url, error.response?.status);
        }
        return Promise.reject(error);
      }
    );
  }

  private writeTrace(method: string | undefined, url: string | undefined, status: number | undefined): void {
    const line = `[kelta-trace] ${(method ?? 'GET').toUpperCase()} ${url ?? ''} ${status ?? 'ERR'}\n`;
    process.stderr.write(line);
  }

  /**
   * Setup retry interceptor with exponential backoff
   */
  private setupRetryInterceptor(): void {
    this.axiosInstance.interceptors.response.use(
      (response) => response,
      async (error) => {
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access
        const config = error.config;

        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access
        config.__retryCount = config.__retryCount || 0;

        // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access
        if (!this.shouldRetry(error) || config.__retryCount >= this.retryConfig.maxAttempts) {
          return Promise.reject(error);
        }

        // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access
        config.__retryCount += 1;

        // Calculate delay with exponential backoff
        // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access
        const delay = Math.min(
          // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access
          1000 * Math.pow(this.retryConfig.backoffMultiplier, config.__retryCount - 1),
          this.retryConfig.maxDelay
        );

        // Wait and retry
        await new Promise((resolve) => setTimeout(resolve, delay));
        // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
        return this.axiosInstance(config);
      }
    );
  }

  /**
   * Determine if an error should trigger a retry
   */
  private shouldRetry(error: unknown): boolean {
    if (!axios.isAxiosError(error)) {
      return false;
    }

    // Retry on network errors
    if (!error.response) {
      return true;
    }

    // Retry on specific status codes
    const status = error.response.status;
    return status === 429 || (status >= 500 && status < 600);
  }
}
