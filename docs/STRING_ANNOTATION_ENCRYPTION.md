# String annotation encryption

`stringAnnotationEncryption` encrypts `String` and `String[]` values stored in annotation attributes on application classes, methods, fields, and type-use annotation locations.

```hocon
stringAnnotationEncryption {
  enabled: true
  exempt: []
}
```

## Supported runtime behavior

Annotation attributes are JVM constants, so the transformer stores encrypted constants in the annotation metadata and rewrites direct annotation accessor calls inside transformed application methods:

```java
annotation.value()
annotation.values()
```

Those direct calls are wrapped with a guarded decryptor. Plaintext values and annotations that were not encrypted are returned unchanged, so mixed transformed and untransformed annotations remain safe for direct accessor calls.

## Unsupported cases

The JVM does not allow annotation element values to be computed by runtime method calls. Because of that, these cases intentionally remain unsupported and may expose encrypted values:

- reflection performed by code that was not transformed by Skidfuscator;
- reflective calls such as `Method.invoke(annotation)` against annotation element methods;
- annotation proxy methods such as `toString()`, `equals()`, and `hashCode()`;
- direct reads of raw class-file annotation attributes by bytecode libraries.

Use transformer exemptions for annotations or classes that must be consumed by untransformed reflection code.
