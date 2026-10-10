/// <reference types="astro/client" />

interface ImportMetaEnv {
  /** Kelta collection URL the /waitlist form POSTs to; empty renders the "not open yet" state. */
  readonly PUBLIC_WAITLIST_ENDPOINT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
