# Product

## Register

brand

## Users

Java developers, plugin authors, and security-conscious software teams who use Skidfuscator to make JVM bytecode harder to statically read, patch, or automatically deobfuscate. They are usually evaluating transformer choices, preparing a protected release, or deciding which hardening tradeoffs are worth testing in their own app.

## Product Purpose

Skidfuscator is a JVM bytecode obfuscator built around SSA, CFG, opaque predicates, seeded flow, string and number encryption, signature changes, method dispatch, and runtime integrity checks. The product exists to raise reverse-engineering cost while keeping protected programs runnable and practical to ship. Success looks like a clear path from default compatibility settings to stronger hardened profiles, with explicit risk and verification guidance.

## Brand Personality

Technical, combative, and precise. The voice should feel like an engineer showing the actual machinery, not a generic cybersecurity landing page. It can be confident and opinionated, but it should avoid theatrical threat imagery and avoid implying that obfuscation is a substitute for server-side security.

## Anti-references

Avoid generic hacker-movie visuals, neon skull aesthetics, vague "military-grade protection" claims, template SaaS cards, ornamental crypto diagrams, and dark dashboards that hide weak explanations behind glow. Avoid presenting client-side obfuscation as impossible to reverse; the right claim is cost, ambiguity, coupling, and automation resistance.

## Design Principles

- Show the mechanism: every hardening claim should connect to a seed path, bytecode shape, transformer, or runtime consequence.
- Make tradeoffs visible: compatibility, runtime cost, and test burden should be surfaced next to the benefit.
- Prefer interactive explanation over slogans: let users toggle seed width, guard compression, KDF coupling, and tamper tiers to see what changes.
- Stay local-first: docs and tools should work offline from the repo when possible.
- Be honest about limits: protected bytecode still runs locally, so the objective is to increase analysis cost and reduce reusable deobfuscation patterns.

## Accessibility & Inclusion

Target WCAG AA contrast for text and controls, keyboard-operable interactions, clear focus states, reduced-motion behavior, and explanations that do not rely on color alone.
