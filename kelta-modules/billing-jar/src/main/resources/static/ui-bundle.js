/**
 * Billing module UI bundle.
 *
 * Loaded by the admin UI from GET /api/modules/kelta-billing/ui-bundle.js and evaluated as an ES
 * module. Its top-level code registers a page component, which makes "Billing Plans" available as
 * a widget in the Page Builder.
 *
 * Deliberately dependency-free and framework-light: it is evaluated from a blob URL in the host
 * page, so it cannot resolve bare specifiers like "react". It renders into a plain DOM node the
 * host mounts, and reads the tenant's own collection through the standard collection API.
 *
 * SECURITY: this runs same-origin with the admin session's full DOM and cookie access. The JAR
 * signature the platform verified at install is the entire trust model — there is no browser-side
 * isolation. Keep this file to what it needs to do.
 */

const COLLECTION_PATH = '/api/billing_plans'

function el(tag, className, text) {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text != null) node.textContent = text
  return node
}

function renderPlans(container, plans) {
  container.replaceChildren()
  const heading = el('h3', 'kelta-billing-heading', 'Billing Plans')
  container.appendChild(heading)

  if (!plans.length) {
    container.appendChild(el('p', 'kelta-billing-empty', 'No active plans yet.'))
    return
  }

  const list = el('ul', 'kelta-billing-list')
  for (const plan of plans) {
    const item = el('li', 'kelta-billing-item')
    item.appendChild(el('span', 'kelta-billing-name', plan.name ?? plan.code ?? 'Untitled plan'))
    if (plan.kind) {
      item.appendChild(el('span', 'kelta-billing-kind', ` · ${plan.kind}`))
    }
    list.appendChild(item)
  }
  container.appendChild(list)
}

/**
 * Reads active plans through the tenant's normal collection route. Values a viewer should never
 * see — Stripe ids, the raw entitlement map — are simply not rendered.
 */
async function loadPlans(container) {
  container.replaceChildren(el('p', 'kelta-billing-loading', 'Loading plans…'))
  try {
    const response = await fetch(`${COLLECTION_PATH}?filter[active][eq]=true`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/vnd.api+json' },
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const body = await response.json()
    const plans = (body.data ?? []).map((row) => row.attributes ?? row)
    renderPlans(container, plans)
  } catch (err) {
    container.replaceChildren(
      el('p', 'kelta-billing-error', 'Could not load billing plans.')
    )
    console.error('[kelta-billing] Failed to load plans:', err)
  }
}

/**
 * The registered page component. The host passes a container element; everything below is plain
 * DOM so the bundle needs no framework of its own.
 */
function BillingPlansWidget() {
  const container = el('div', 'kelta-billing-widget')
  loadPlans(container)
  return container
}

const registry = globalThis.__keltaComponentRegistry
if (registry && typeof registry.registerPageComponent === 'function') {
  registry.registerPageComponent('billing-plans', '/billing/plans', BillingPlansWidget)
  console.info('[kelta-billing] Registered page component: billing-plans')
} else {
  console.warn('[kelta-billing] Component registry unavailable — UI not registered')
}

export { BillingPlansWidget }
