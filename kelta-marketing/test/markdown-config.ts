import { isUnifiedProcessor, type UnifiedResolvedOptions } from '@astrojs/markdown-remark';
import astroConfig from '../astro.config.mjs';

/** The remark/rehype pipeline astro.config.mjs hands to the unified Markdown processor. */
export function unifiedOptions(): UnifiedResolvedOptions {
  const processor = astroConfig.markdown?.processor;
  if (!processor || !isUnifiedProcessor(processor)) throw new Error('astro.config.mjs must set markdown.processor: unified({...})');
  return processor.options;
}
