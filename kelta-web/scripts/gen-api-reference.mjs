// Renders docs/authoring/api-{sdk,components,plugin-sdk,formula}.md — one API
// reference page per package, listing every symbol its src/index.ts exports —
// with the TypeScript compiler API. Run: npm run gen:api-reference. Freshness
// is enforced by scripts/apiReference.test.ts.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
export const REPO_ROOT = join(webRoot, '..');

export const PACKAGES = [
  { dir: 'sdk', name: '@kelta/sdk', guide: '/docs/sdk/sdk/' },
  { dir: 'components', name: '@kelta/components', guide: '/docs/sdk/components/' },
  { dir: 'plugin-sdk', name: '@kelta/plugin-sdk', guide: '/docs/sdk/plugin-sdk/' },
  { dir: 'formula', name: '@kelta/formula', guide: '/docs/sdk/formula/' },
];

export const pagePath = (pkg) => `docs/authoring/api-${pkg.dir}.md`;

const KINDS = [
  { id: 'class', heading: 'Classes', flag: ts.SymbolFlags.Class },
  { id: 'function', heading: 'Functions', flag: ts.SymbolFlags.Function },
  { id: 'variable', heading: 'Constants', flag: ts.SymbolFlags.Variable },
  { id: 'enum', heading: 'Enums', flag: ts.SymbolFlags.Enum },
  { id: 'interface', heading: 'Interfaces', flag: ts.SymbolFlags.Interface },
  { id: 'type', heading: 'Type aliases', flag: ts.SymbolFlags.TypeAlias },
];

const TYPE_FLAGS =
  ts.TypeFormatFlags.NoTruncation |
  ts.TypeFormatFlags.UseAliasDefinedOutsideCurrentScope |
  ts.TypeFormatFlags.UseSingleQuotesForStringLiteralType;

const printer = ts.createPrinter({ removeComments: true, newLine: ts.NewLineKind.LineFeed });

function entryFile(pkg) {
  return join(webRoot, 'packages', pkg.dir, 'src', 'index.ts');
}

/** One program over all four entry points, compiled with the workspace's root options. */
export function createApiProgram() {
  const configPath = join(webRoot, 'tsconfig.json');
  const { config } = ts.readConfigFile(configPath, ts.sys.readFile);
  const { options } = ts.convertCompilerOptionsFromJson(
    config.compilerOptions,
    webRoot,
    configPath
  );
  return ts.createProgram(PACKAGES.map(entryFile), { ...options, noEmit: true, composite: false });
}

/** `import("/abs/path/x").Foo` → `Foo`, so no checkout path leaks into the page. */
function clean(text) {
  return text.replace(/import\("[^"]*"\)\./g, '');
}

function typeText(checker, type, node) {
  return clean(checker.typeToString(type, node, TYPE_FLAGS));
}

function signatureText(checker, signature, node) {
  return clean(checker.signatureToString(signature, node, TYPE_FLAGS));
}

/** Reprints a declaration so long object types break one member per line. */
function format(text) {
  const source = ts.createSourceFile(
    'signature.d.ts',
    text,
    ts.ScriptTarget.Latest,
    false,
    ts.ScriptKind.TS
  );
  return printer.printFile(source).trimEnd();
}

function printNode(node) {
  return clean(printer.printNode(ts.EmitHint.Unspecified, node, node.getSourceFile()));
}

function kindOf(symbol) {
  return KINDS.find((k) => symbol.flags & k.flag) ?? KINDS.find((k) => k.id === 'variable');
}

function isPublic(member) {
  const decl = member.valueDeclaration ?? member.declarations?.[0];
  if (!decl) return true;
  if (decl.name && ts.isPrivateIdentifier(decl.name)) return false;
  const flags = ts.getCombinedModifierFlags(decl);
  return !(flags & (ts.ModifierFlags.Private | ts.ModifierFlags.Protected));
}

/** The class's own public members — inherited ones belong to the `extends` target's entry. */
function memberLines(checker, type, node, prefix) {
  const lines = [];
  for (const member of checker.getPropertiesOfType(type)) {
    if (!isPublic(member) || !member.declarations?.some((d) => d.parent === node)) continue;
    const memberType = checker.getTypeOfSymbolAtLocation(member, node);
    const decl = member.valueDeclaration ?? member.declarations?.[0];
    const modifiers = decl ? ts.getCombinedModifierFlags(decl) : 0;
    const readonly = modifiers & ts.ModifierFlags.Readonly ? 'readonly ' : '';
    const abstract = modifiers & ts.ModifierFlags.Abstract ? 'abstract ' : '';
    if (member.flags & ts.SymbolFlags.Method) {
      for (const sig of checker.getSignaturesOfType(memberType, ts.SignatureKind.Call)) {
        lines.push(`  ${prefix}${abstract}${member.name}${signatureText(checker, sig, node)};`);
      }
    } else {
      const optional = member.flags & ts.SymbolFlags.Optional ? '?' : '';
      lines.push(
        `  ${prefix}${abstract}${readonly}${member.name}${optional}: ${typeText(checker, memberType, node)};`
      );
    }
  }
  return lines;
}

function classSignature(checker, name, decl) {
  const typeParams = decl.typeParameters
    ? `<${decl.typeParameters.map(printNode).join(', ')}>`
    : '';
  const heritage = (decl.heritageClauses ?? []).map((h) => ` ${printNode(h).trim()}`).join('');
  const abstract = ts.getCombinedModifierFlags(decl) & ts.ModifierFlags.Abstract ? 'abstract ' : '';
  const staticType = checker.getTypeOfSymbolAtLocation(
    checker.getSymbolAtLocation(decl.name),
    decl
  );
  const instanceType = checker.getDeclaredTypeOfSymbol(checker.getSymbolAtLocation(decl.name));
  const ctors = decl.members
    .filter((m) => ts.isConstructorDeclaration(m) && isPublic({ valueDeclaration: m }))
    .map((m) => {
      const params = m.parameters.map((p) => {
        const optional = p.questionToken || p.initializer ? '?' : '';
        const rest = p.dotDotDotToken ? '...' : '';
        const type = typeText(checker, checker.getTypeAtLocation(p), decl);
        return `${rest}${p.name.getText()}${optional}: ${type}`;
      });
      return `  constructor(${params.join(', ')});`;
    });
  return [
    `${abstract}class ${name}${typeParams}${heritage} {`,
    ...memberLines(checker, staticType, decl, 'static '),
    ...ctors,
    ...memberLines(checker, instanceType, decl, ''),
    '}',
  ].join('\n');
}

function renamed(text, from, to) {
  return from === to ? text : text.replace(new RegExp(`\\b${from}\\b`), to);
}

function signatureOf(checker, name, target, kind) {
  const decl = target.valueDeclaration ?? target.declarations[0];
  switch (kind.id) {
    case 'class':
      return classSignature(checker, name, target.declarations.find(ts.isClassDeclaration));
    case 'function': {
      const type = checker.getTypeOfSymbolAtLocation(target, decl);
      return checker
        .getSignaturesOfType(type, ts.SignatureKind.Call)
        .map((sig) => `function ${name}${signatureText(checker, sig, decl)};`)
        .join('\n');
    }
    case 'variable':
      return `const ${name}: ${typeText(checker, checker.getTypeOfSymbolAtLocation(target, decl), decl)};`;
    default:
      return target.declarations
        .filter(
          (d) =>
            ts.isInterfaceDeclaration(d) || ts.isTypeAliasDeclaration(d) || ts.isEnumDeclaration(d)
        )
        .map((d) => renamed(printNode(d).replace(/^export (declare )?/, ''), target.name, name))
        .join('\n');
  }
}

function firstParagraph(checker, symbol) {
  const text = ts.displayPartsToString(symbol.getDocumentationComment(checker)).trim();
  return text.split(/\n\s*\n/)[0].replace(/\s*\n\s*/g, ' ');
}

function isDeprecated(symbol) {
  return symbol.declarations?.some((d) =>
    ts.getJSDocTags(d).some((t) => t.tagName.text === 'deprecated')
  );
}

function collect(program, pkg) {
  const checker = program.getTypeChecker();
  const source = program.getSourceFile(entryFile(pkg));
  const moduleSymbol = checker.getSymbolAtLocation(source);
  return checker.getExportsOfModule(moduleSymbol).map((exported) => {
    const target =
      exported.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(exported) : exported;
    const kind = kindOf(target);
    return {
      name: exported.name,
      kind,
      signature: format(signatureOf(checker, exported.name, target, kind)),
      doc: firstParagraph(checker, target),
      deprecated: isDeprecated(target),
    };
  });
}

/** The markdown for one package's page. Pure given the program: sorted, no dates, no absolute paths. */
export function renderApiReference(program, pkg) {
  const symbols = collect(program, pkg).sort((a, b) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : 0
  );
  const out = [
    `<!-- GENERATED by kelta-web/scripts/gen-api-reference.mjs from kelta-web/packages/${pkg.dir}/src/index.ts — do not edit by hand; run \`npm run gen:api-reference\` in kelta-web -->`,
    '',
    `# ${pkg.name} — API Reference`,
    '',
    `Every symbol exported from \`${pkg.name}\`, with its TypeScript signature and the first paragraph of its`,
    `doc comment — generated from \`kelta-web/packages/${pkg.dir}/src/index.ts\`. For a guided tour with`,
    `examples, see [${pkg.name}](${pkg.guide}).`,
    '',
    `${symbols.length} exports, grouped by kind and sorted by name.`,
  ];
  for (const kind of KINDS) {
    const group = symbols.filter((s) => s.kind === kind);
    if (group.length === 0) continue;
    out.push('', `## ${kind.heading}`);
    for (const s of group) {
      out.push('', `### ${s.name}`, '');
      if (s.deprecated) out.push('**Deprecated.**', '');
      if (s.doc) out.push(s.doc, '');
      out.push('```ts', s.signature, '```');
    }
  }
  return `${out.join('\n')}\n`;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const program = createApiProgram();
  for (const pkg of PACKAGES) {
    const path = pagePath(pkg);
    const page = renderApiReference(program, pkg);
    let previous = '';
    try {
      previous = readFileSync(join(REPO_ROOT, path), 'utf-8');
    } catch {
      // first run
    }
    writeFileSync(join(REPO_ROOT, path), page);
    console.log(`${previous === page ? 'Unchanged' : 'Wrote'} ${path}`);
  }
}
