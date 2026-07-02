#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const OUT_DIR = path.join(ROOT, "docs", "class-web");
const OUT_FILE = path.join(OUT_DIR, "class-data.js");

const SKIP_DIRS = new Set([
  ".git",
  ".gradle",
  ".idea",
  "build",
  "target",
  "out",
  "node_modules",
  "dist",
]);

const MODIFIERS = new Set([
  "public",
  "private",
  "protected",
  "static",
  "final",
  "abstract",
  "synchronized",
  "native",
  "strictfp",
  "default",
  "transient",
  "volatile",
  "sealed",
  "non-sealed",
]);

const THREAD_SEED_PATTERNS = [
  ["seed-thread", "seed threading", /\b(threadStaticMethods|threadReturnKey|threadKey|getThreadedStringSeed|getThreadedStringSeedLong|getThreadedStringSeedExpr|getThreadedStringSeedExprWide|setInjectedMethodPredicate|isInjectedMethodPredicate)\b/i],
  ["flow-seed", "flow seed", /\b(threaded flow seed|flow seed|seed\.wide|PredicateFlowGetter|PredicateFlowSetter|getPublicLong|getPrivateLong|getPublic\(\)|getPrivate\(\))\b/i],
  ["descriptor-thread", "descriptor threading", /\b(insertParameter|stackHeight|setStackHeight|getStackHeight|parameterGroup|seed parameter)\b/i],
];

const THREAD_SAFE_PATTERNS = [
  ["synchronized", "synchronized block or method", /\bsynchronized\b/],
  ["concurrent", "concurrent collection", /\bConcurrent[A-Za-z0-9_]*\b|\bCopyOnWriteArrayList\b/],
  ["atomic", "atomic guard", /\bAtomic(?:Boolean|Integer|Long|Reference|LongArray|IntegerArray)?\b/],
  ["volatile", "volatile state", /\bvolatile\b/],
  ["thread-local", "thread local state", /\bThreadLocal\b/],
  ["interrupt", "interrupt/cancel handling", /\b(?:isInterrupted|InterruptedException|Thread\.currentThread\(\)\.interrupt|isCancelled|cancel\()\b/],
  ["daemon", "thread lifecycle flag", /\bsetDaemon\(/],
  ["swing-handoff", "Swing handoff", /\bSwingUtilities\.invokeLater\b/],
];

const THREAD_ASYNC_PATTERNS = [
  ["new-thread", "spawns thread", /\bnew\s+Thread\s*\(/],
  ["worker", "background worker", /\bSwingWorker\b/],
  ["future", "future/async work", /\bCompletableFuture\b|\bsupplyAsync\s*\(/],
  ["executor", "executor service", /\bExecutor(?:Service)?\b|\bExecutors\./],
  ["sleep", "blocking sleep", /\bThread\.sleep\s*\(/],
  ["wait-notify", "wait/notify", /\.(?:wait|notify|notifyAll)\s*\(/],
];

const RESTRICTION_PATTERNS = [
  ["exemption", "exemption gate", /\b(?:isExempt|ExclusionParser|ExclusionMap|ExclusionTester|Exclude|MethodExempt|BlockExempt|exemptions?|@Exclude)\b/i],
  ["native-abstract", "native/abstract guard", /\b(?:ACC_NATIVE|ACC_ABSTRACT|isNative|isAbstract|native-sensitive|NativeObfuscation|JNativeHook|JNA|native method)\b/i],
  ["init", "constructor/static-init guard", /\b(?:isInit|isClinit|<init>|<clinit>|constructor|static initializer)\b/i],
  ["synthetic", "synthetic member guard", /\b(?:ACC_SYNTHETIC|isSynthetic|synthetic)\b/i],
  ["inner-class", "inner/anonymous guard", /\b(?:outerClass|nestHostClass|innerClasses|anonymous|inner class|nested class|fragile owner|hasFragile)\b/i],
  ["dynamic-invoke", "dynamic invocation guard", /\b(?:DynamicInvocationExpr|InvokeDynamic|invokedynamic|bootstrap|Handle|handleReferences)\b/i],
  ["entry-point", "entry point guard", /\b(?:isEntryPoint|entryPoint|invokers\.isEmpty|direct invocation|reflection calls)\b/i],
  ["override", "override/signature guard", /(?<!@)\boverride(?:s|d| lookup)?\b|\b(?:descriptor collision|declarers|handle references|collisions?|newDesc|oldDesc)\b/i],
  ["size-risk", "size/callsite limit", /\b(?:MAX_REWRITE|too large|size risky|limit|threshold|callsite)\b/i],
  ["unsupported", "unsupported path", /\b(?:UnsupportedOperationException|unsupported|not supported|cannot|can't|skip|skipping|only when|required|requires|must)\b/i],
  ["unsafe", "unsafe/native-memory access", /\b(?:UnsafeAccess|sun\.misc\.Unsafe|Access\.unsafe|native order|objectFieldOffset)\b/i],
  ["runtime-reaction", "runtime reaction", /\b(?:Runtime\.getRuntime\(\)\.halt|verifySilent|verifyExit|detonate|tamper|checksum)\b/i],
];

function walk(dir, files = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name) && !entry.name.startsWith(".")) {
        walk(path.join(dir, entry.name), files);
      }
      continue;
    }
    if (entry.isFile() && entry.name.endsWith(".java")) {
      files.push(path.join(dir, entry.name));
    }
  }
  return files;
}

function maskJava(source) {
  let out = "";
  let state = "code";
  for (let i = 0; i < source.length; i += 1) {
    const c = source[i];
    const n = source[i + 1];
    if (state === "code") {
      if (c === "/" && n === "/") {
        out += "  ";
        i += 1;
        state = "line";
      } else if (c === "/" && n === "*") {
        out += "  ";
        i += 1;
        state = "block";
      } else if (c === "\"") {
        out += " ";
        state = "string";
      } else if (c === "'") {
        out += " ";
        state = "char";
      } else {
        out += c;
      }
    } else if (state === "line") {
      if (c === "\n") {
        out += "\n";
        state = "code";
      } else {
        out += " ";
      }
    } else if (state === "block") {
      if (c === "*" && n === "/") {
        out += "  ";
        i += 1;
        state = "code";
      } else {
        out += c === "\n" ? "\n" : " ";
      }
    } else if (state === "string") {
      if (c === "\\" && n != null) {
        out += "  ";
        i += 1;
      } else if (c === "\"") {
        out += " ";
        state = "code";
      } else {
        out += c === "\n" ? "\n" : " ";
      }
    } else if (state === "char") {
      if (c === "\\" && n != null) {
        out += "  ";
        i += 1;
      } else if (c === "'") {
        out += " ";
        state = "code";
      } else {
        out += c === "\n" ? "\n" : " ";
      }
    }
  }
  return out;
}

function buildLineIndex(source) {
  const starts = [0];
  for (let i = 0; i < source.length; i += 1) {
    if (source[i] === "\n") starts.push(i + 1);
  }
  return starts;
}

function lineAt(starts, index) {
  let lo = 0;
  let hi = starts.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (starts[mid] <= index) lo = mid + 1;
    else hi = mid - 1;
  }
  return hi + 1;
}

function findMatchingBrace(masked, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < masked.length; i += 1) {
    if (masked[i] === "{") depth += 1;
    else if (masked[i] === "}") {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return masked.length - 1;
}

function normalizeWhitespace(value) {
  return value.replace(/\s+/g, " ").trim();
}

function splitTopLevelTypes(value) {
  const parts = [];
  let current = "";
  let angle = 0;
  for (const c of value) {
    if (c === "<") angle += 1;
    if (c === ">") angle = Math.max(0, angle - 1);
    if (c === "," && angle === 0) {
      if (current.trim()) parts.push(cleanTypeName(current));
      current = "";
    } else {
      current += c;
    }
  }
  if (current.trim()) parts.push(cleanTypeName(current));
  return parts.filter(Boolean);
}

function cleanTypeName(value) {
  return value
    .replace(/<[^<>]*(?:<[^<>]*>[^<>]*)*>/g, "")
    .replace(/\b(?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\b/g, "")
    .replace(/\[\]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .split(/\s+/)
    .pop()
    ?.replace(/[^\w.$]/g, "") ?? "";
}

function parseTypeRelations(tail) {
  const cleaned = normalizeWhitespace(tail.replace(/<[^{};]+>\s*/, ""));
  const relations = { extends: [], implements: [] };
  const ext = cleaned.match(/\bextends\s+(.+?)(?=\s+implements\b|\s+permits\b|$)/);
  if (ext) relations.extends = splitTopLevelTypes(ext[1]);
  const impl = cleaned.match(/\bimplements\s+(.+?)(?=\s+permits\b|$)/);
  if (impl) relations.implements = splitTopLevelTypes(impl[1]);
  if (!impl && /\binterface\b/.test(cleaned)) {
    relations.implements = [];
  }
  return relations;
}

function parseModifiers(prefix) {
  const tokens = normalizeWhitespace(prefix)
    .replace(/@\w+(?:\([^)]*\))?/g, " ")
    .split(/\s+/)
    .filter(Boolean);
  return [...new Set(tokens.filter((token) => MODIFIERS.has(token)))];
}

function discoverClasses(file, source, masked, starts, packageName) {
  const classes = [];
  const classRe = /((?:(?:@\w+(?:\([^)]*\))?\s*)|(?:(?:public|private|protected|abstract|static|final|sealed|non-sealed|strictfp)\s+))*)(@interface|class|interface|enum|record)\s+([A-Za-z_$][\w$]*)([^{};]*)\{/g;
  let match;
  while ((match = classRe.exec(masked))) {
    const open = masked.indexOf("{", classRe.lastIndex - 1);
    if (open < 0) continue;
    const close = findMatchingBrace(masked, open);
    const kind = match[2] === "@interface" ? "annotation" : match[2];
    const simpleName = match[3];
    classes.push({
      file,
      packageName,
      simpleName,
      kind,
      modifiers: parseModifiers(match[1] ?? ""),
      tail: match[4] ?? "",
      start: match.index,
      open,
      close,
      line: lineAt(starts, match.index),
    });
    classRe.lastIndex = open + 1;
  }

  classes.sort((a, b) => a.start - b.start || b.close - a.close);
  for (const cls of classes) {
    const parent = classes
      .filter((candidate) => candidate !== cls && candidate.start < cls.start && candidate.close > cls.close)
      .sort((a, b) => (b.start - a.start))[0];
    cls.parent = parent ?? null;
    const localName = parent ? `${parent.localName}$${cls.simpleName}` : cls.simpleName;
    cls.localName = localName;
    cls.fullName = packageName ? `${packageName}.${localName}` : localName;
    cls.id = cls.fullName;
    Object.assign(cls, parseTypeRelations(cls.tail));
  }
  return classes;
}

function isMethodDeclaration(segment, classSimpleName) {
  const sig = normalizeWhitespace(segment);
  if (!sig.includes("(") || !sig.includes(")")) return false;
  if (/=\s*(?:new\s+)?[A-Za-z_$][\w$<>.]*\s*\(/.test(sig)) return false;
  const match = sig.match(/([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[\w$.,\s<>]+)?$/);
  if (!match) return false;
  const name = match[1];
  if (["if", "for", "while", "switch", "catch", "try", "new", "return", "throw", "super", "this", "do"].includes(name)) {
    return false;
  }
  if (name === classSimpleName) return true;
  const before = sig.slice(0, match.index).trim();
  return before.length > 0;
}

function parseMethodSignature(rawSegment, maskedSegment, classSimpleName) {
  const raw = normalizeWhitespace(rawSegment.replace(/\s*\{\s*$/, ""));
  const masked = normalizeWhitespace(maskedSegment.replace(/\s*\{\s*$/, ""));
  const nameMatch = masked.match(/([A-Za-z_$][\w$]*)\s*\(([^;{}]*)\)\s*(?:throws\s+(.+))?$/);
  if (!nameMatch) return null;

  const name = nameMatch[1];
  const params = normalizeWhitespace(nameMatch[2] ?? "");
  const prefix = masked.slice(0, nameMatch.index).trim();
  const modifiers = parseModifiers(prefix);
  const constructor = name === classSimpleName;
  let returnType = constructor ? classSimpleName : "";
  if (!constructor) {
    let typePrefix = prefix
      .replace(/@\w+(?:\([^)]*\))?/g, " ")
      .replace(/\b(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|default)\b/g, " ")
      .replace(/^\s*<[^>]+>\s*/, " ")
      .trim();
    returnType = typePrefix.split(/\s+/).pop() ?? "";
  }

  return {
    name,
    params,
    modifiers,
    returnType,
    constructor,
    throws: normalizeWhitespace(nameMatch[3] ?? ""),
    signature: raw.length > 220 ? `${raw.slice(0, 217)}...` : raw,
  };
}

function parseMethods(source, masked, starts, cls) {
  const methods = [];
  let last = cls.open + 1;
  let i = cls.open + 1;
  while (i < cls.close) {
    const ch = masked[i];
    if (ch === "{") {
      const maskedSegment = masked.slice(last, i);
      const rawSegment = source.slice(last, i);
      if (isMethodDeclaration(maskedSegment, cls.simpleName)) {
        const close = findMatchingBrace(masked, i);
        const signature = parseMethodSignature(rawSegment, maskedSegment, cls.simpleName);
        if (signature) {
          const leading = rawSegment.search(/\S/);
          const start = last + (leading < 0 ? 0 : leading);
          methods.push({
            ...signature,
            line: lineAt(starts, start),
            endLine: lineAt(starts, close),
            body: source.slice(i + 1, close),
            declaration: rawSegment,
            hasBody: true,
          });
        }
        i = close + 1;
        last = i;
        continue;
      }
      const close = findMatchingBrace(masked, i);
      i = close + 1;
      last = i;
      continue;
    }
    if (ch === ";") {
      const maskedSegment = masked.slice(last, i);
      const rawSegment = source.slice(last, i);
      if (isMethodDeclaration(maskedSegment, cls.simpleName)) {
        const signature = parseMethodSignature(rawSegment, maskedSegment, cls.simpleName);
        if (signature) {
          const leading = rawSegment.search(/\S/);
          const start = last + (leading < 0 ? 0 : leading);
          methods.push({
            ...signature,
            line: lineAt(starts, start),
            endLine: lineAt(starts, i),
            body: "",
            declaration: rawSegment,
            hasBody: false,
          });
        }
      }
      last = i + 1;
    }
    i += 1;
  }
  return methods;
}

function evidenceFor(text, baseLine, patterns, limit = 4) {
  const hits = [];
  for (const [key, label, re] of patterns) {
    const flags = re.flags.includes("g") ? re.flags : `${re.flags}g`;
    const global = new RegExp(re.source, flags);
    const match = global.exec(text);
    if (!match) continue;
    const before = text.slice(0, match.index);
    const line = baseLine + (before.match(/\n/g)?.length ?? 0);
    const lineText = text.split(/\r?\n/)[line - baseLine] ?? "";
    hits.push({
      key,
      label,
      line,
      match: match[0],
      snippet: normalizeWhitespace(lineText).slice(0, 180),
    });
    if (hits.length >= limit) break;
  }
  return hits;
}

function classifyMethod(method) {
  const text = `${method.declaration}\n${method.body}`;
  const seedEvidence = evidenceFor(text, method.line, THREAD_SEED_PATTERNS);
  const safeEvidence = evidenceFor(text, method.line, THREAD_SAFE_PATTERNS);
  const asyncEvidence = evidenceFor(text, method.line, THREAD_ASYNC_PATTERNS);
  const restrictionEvidence = evidenceFor(text, method.line, RESTRICTION_PATTERNS, 8);

  for (const modifier of method.modifiers) {
    if (["native", "abstract", "synchronized"].includes(modifier)) {
      restrictionEvidence.unshift({
        key: `modifier-${modifier}`,
        label: `${modifier} modifier`,
        line: method.line,
        match: modifier,
        snippet: method.signature,
      });
    }
  }

  const hasLifecycleEvidence = safeEvidence.length > 0
    || /\b(?:setDaemon|isCancelled|isInterrupted|invokeLater|compareAndSet|finally)\b/.test(text);

  let threadStatus = "neutral";
  if (asyncEvidence.length > 0 && !hasLifecycleEvidence && seedEvidence.length === 0) {
    threadStatus = "review";
  } else if (seedEvidence.length > 0) {
    threadStatus = "seed";
  } else if (safeEvidence.length > 0 || asyncEvidence.length > 0) {
    threadStatus = "aware";
  }

  return {
    threadStatus,
    restricted: restrictionEvidence.length > 0,
    evidence: {
      seed: seedEvidence,
      safe: safeEvidence,
      async: asyncEvidence,
      restrictions: restrictionEvidence,
    },
  };
}

function packageFrom(source) {
  return source.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1] ?? "";
}

function importsFrom(source) {
  return [...source.matchAll(/^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/gm)].map((m) => m[1]);
}

function moduleInfo(relativePath) {
  const parts = relativePath.split(path.sep);
  const src = parts.indexOf("src");
  const module = src > 0 ? parts.slice(0, src).join("/") : "(root)";
  let sourceSet = "misc";
  if (src >= 0 && parts[src + 1]) sourceSet = parts[src + 1];
  return { module, sourceSet };
}

function resolveType(typeName, fromClass, indexes) {
  if (!typeName) return null;
  const cleaned = cleanTypeName(typeName);
  if (!cleaned) return null;
  if (indexes.byFull.has(cleaned)) return cleaned;
  if (fromClass.packageName && indexes.byFull.has(`${fromClass.packageName}.${cleaned}`)) {
    return `${fromClass.packageName}.${cleaned}`;
  }
  const imported = fromClass.imports.find((imp) => imp.endsWith(`.${cleaned}`));
  if (imported && indexes.byFull.has(imported)) return imported;
  const candidates = indexes.bySimple.get(cleaned) ?? [];
  if (candidates.length === 1) return candidates[0];
  const samePackage = candidates.find((candidate) => candidate.startsWith(`${fromClass.packageName}.`));
  return samePackage ?? null;
}

function referenceTokens(maskedBody) {
  return new Set(maskedBody.match(/\b[A-Z][A-Za-z0-9_$]*\b/g) ?? []);
}

function statusRank(status) {
  return { review: 4, seed: 3, aware: 2, neutral: 1 }[status] ?? 0;
}

function aggregateClassStatus(methods) {
  let threadStatus = "neutral";
  const counts = { seed: 0, aware: 0, review: 0, neutral: 0, restricted: 0 };
  const restrictionTypes = new Map();
  for (const method of methods) {
    counts[method.threadStatus] = (counts[method.threadStatus] ?? 0) + 1;
    if (statusRank(method.threadStatus) > statusRank(threadStatus)) {
      threadStatus = method.threadStatus;
    }
    if (method.restricted) counts.restricted += 1;
    for (const hit of method.evidence.restrictions) {
      restrictionTypes.set(hit.key, (restrictionTypes.get(hit.key) ?? 0) + 1);
    }
  }
  return {
    threadStatus,
    counts,
    restrictionTypes: [...restrictionTypes.entries()].map(([key, count]) => ({ key, count })),
  };
}

function buildData() {
  const javaFiles = walk(ROOT).sort();
  const classRecords = [];
  const fileRecords = [];

  for (const absolute of javaFiles) {
    const relativePath = path.relative(ROOT, absolute);
    const source = fs.readFileSync(absolute, "utf8");
    const masked = maskJava(source);
    const starts = buildLineIndex(source);
    const packageName = packageFrom(source);
    const imports = importsFrom(source);
    const info = moduleInfo(relativePath);
    const discovered = discoverClasses(relativePath, source, masked, starts, packageName);

    fileRecords.push({
      path: relativePath,
      module: info.module,
      sourceSet: info.sourceSet,
      packageName,
      classCount: discovered.length,
      lineCount: starts.length,
    });

    for (const cls of discovered) {
      const methods = parseMethods(source, masked, starts, cls).map((method) => ({
        name: method.name,
        signature: method.signature,
        params: method.params,
        returnType: method.returnType,
        modifiers: method.modifiers,
        constructor: method.constructor,
        line: method.line,
        endLine: method.endLine,
        hasBody: method.hasBody,
        ...classifyMethod(method),
      }));
      const aggregate = aggregateClassStatus(methods);
      const classBodyMasked = masked.slice(cls.open + 1, cls.close);
      classRecords.push({
        id: cls.id,
        simpleName: cls.simpleName,
        localName: cls.localName,
        packageName: cls.packageName,
        module: info.module,
        sourceSet: info.sourceSet,
        file: relativePath,
        line: cls.line,
        kind: cls.kind,
        modifiers: cls.modifiers,
        parent: cls.parent?.fullName ?? null,
        extends: cls.extends,
        implements: cls.implements,
        imports,
        tokens: [...referenceTokens(classBodyMasked)],
        methodCount: methods.length,
        methods,
        ...aggregate,
      });
    }
  }

  const byFull = new Map(classRecords.map((cls) => [cls.id, cls]));
  const bySimple = new Map();
  for (const cls of classRecords) {
    if (!bySimple.has(cls.simpleName)) bySimple.set(cls.simpleName, []);
    bySimple.get(cls.simpleName).push(cls.id);
  }
  const indexes = { byFull, bySimple };
  const edgeMap = new Map();

  function addEdge(source, target, type, weight = 1) {
    if (!target || source === target || !byFull.has(target)) return;
    const key = `${source}->${target}:${type}`;
    const existing = edgeMap.get(key);
    if (existing) existing.weight += weight;
    else edgeMap.set(key, { source, target, type, weight });
  }

  for (const cls of classRecords) {
    if (cls.parent) addEdge(cls.id, cls.parent, "nested", 2);
    for (const type of cls.extends) addEdge(cls.id, resolveType(type, cls, indexes), "extends", 4);
    for (const type of cls.implements) addEdge(cls.id, resolveType(type, cls, indexes), "implements", 3);
    for (const imp of cls.imports) {
      if (imp.endsWith(".*")) continue;
      addEdge(cls.id, resolveType(imp, cls, indexes), "import", 1);
    }
    for (const token of cls.tokens) {
      const target = resolveType(token, cls, indexes);
      if (target) addEdge(cls.id, target, "reference", 1);
    }
    delete cls.imports;
    delete cls.tokens;
  }

  const totals = {
    files: fileRecords.length,
    classes: classRecords.length,
    methods: classRecords.reduce((sum, cls) => sum + cls.methodCount, 0),
    seedThreadedMethods: classRecords.reduce((sum, cls) => sum + cls.counts.seed, 0),
    threadAwareMethods: classRecords.reduce((sum, cls) => sum + cls.counts.aware, 0),
    reviewMethods: classRecords.reduce((sum, cls) => sum + cls.counts.review, 0),
    restrictedMethods: classRecords.reduce((sum, cls) => sum + cls.counts.restricted, 0),
    edges: edgeMap.size,
  };

  const modules = [...new Set(classRecords.map((cls) => cls.module))].sort();
  const packages = [...new Set(classRecords.map((cls) => cls.packageName).filter(Boolean))].sort();
  const restrictionTypes = new Map();
  for (const cls of classRecords) {
    for (const entry of cls.restrictionTypes) {
      restrictionTypes.set(entry.key, (restrictionTypes.get(entry.key) ?? 0) + entry.count);
    }
  }

  return {
    generatedAt: new Date().toISOString(),
    root: path.basename(ROOT),
    totals,
    modules,
    packages,
    restrictionTypes: [...restrictionTypes.entries()]
      .map(([key, count]) => ({ key, count }))
      .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key)),
    classes: classRecords.sort((a, b) => a.id.localeCompare(b.id)),
    edges: [...edgeMap.values()].sort((a, b) => b.weight - a.weight),
    files: fileRecords,
  };
}

const data = buildData();
fs.mkdirSync(OUT_DIR, { recursive: true });
const payload = `window.CLASS_WEB_DATA = ${JSON.stringify(data, null, 2)};\n`;
fs.writeFileSync(OUT_FILE, payload);
console.log(`Wrote ${path.relative(ROOT, OUT_FILE)}`);
console.log(`${data.totals.classes} classes, ${data.totals.methods} methods, ${data.totals.edges} edges`);
