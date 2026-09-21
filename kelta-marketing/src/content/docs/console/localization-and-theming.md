---
title: Translations, locales and theming
description: Built-in locales, per-tenant translation overrides and how keys resolve, plus what can be themed today.
section: console
order: 90
---

## Locales

The interface ships in **English, Arabic, German, Spanish, French and Portuguese** (`en ar de es fr pt`); Arabic
renders right-to-left. A user's locale is detected from the browser, stored as a preference, and can be changed
from the user menu. Dates, numbers and currencies follow the locale.

## Tenant translations

Any interface string can be overridden per tenant and locale, and new keys added for your own pages:

- Setup → Platform → Translations (`CUSTOMIZE_APPLICATION`) edits rows of the `ui-translations` collection:
  `locale`, `key`, `value`.
- Resolution order at render time: **tenant override → locale bundle → English bundle → the inline default**.
- `{{param}}` placeholders interpolate exactly as in the built-in bundles.
- Changes are broadcast to every pod and reach users on their next app load; the first 2 000 rows are loaded with
  the app bootstrap.

Metadata labels (collection and field display names) are edited on the metadata itself, not as translations.

## Theme

Users choose **light, dark or system** from the user menu; the choice is stored in the browser.

Tenant-level branding — logo, primary colour, favicon — is defined in the app's bootstrap contract but is not
yet configurable from the console; today only the application name is derived from the tenant. A custom domain
gives the tenant its own hostname ([Tenants](/docs/platform/tenants/)).
