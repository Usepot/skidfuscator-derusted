# Login CTF obfuscation fixture

This dependency-free Java 8 console application verifies that Skidfuscator's
operational Java-bytecode transformations preserve authentication behavior.
It is deliberately a CTF fixture and must not be copied as a production login
system.

## Credentials

- Username: `maple`
- Password: `RedMaple!2026`
- Successful flag: `SKID{maple_ir_obfuscation_verified}`

The application stores SHA-256 values for the credentials, not their plaintext
values. The flag is returned by a separate method so the string encryption and
control-flow transformations have a small, observable target.

## Build, obfuscate, and verify

From this directory on Windows PowerShell:

```powershell
.\build-and-obfuscate.ps1
.\verify.ps1
```

The scripts create:

```text
build/login-ctf-original.jar
build/login-ctf-obfuscated.jar
```

Manual execution:

```powershell
java -jar .\build\login-ctf-obfuscated.jar
```

`skidfuscator.hocon` keeps native mode disabled for the normal fixture. Signed
host-native AOT and VM acceptance are exercised separately:

```powershell
.\build-obfuscate-native.ps1
```

This requires a signed, installed SkidLLVM host toolchain whose manifest and
signature are accepted by the public key pinned in Skidfuscator. Run either
backend with an explicit installation directory:

```powershell
.\build-obfuscate-native.ps1 -SkidLLVMPath C:\absolute\path\to\SkidLLVM -Backend AOT
.\build-obfuscate-native.ps1 -SkidLLVMPath C:\absolute\path\to\SkidLLVM -Backend VM
```

It creates and verifies:

```text
build/login-ctf-native.jar
build/native-artifacts/login-ctf-native-<target>.jar
```

The fixture converts the private static `revealFlag()` method into a registered
JNI function and compares the protected executable's outputs, errors, exit
codes, and secret visibility against the original. The repository-wide signed
native CI applies the same check to both backends, six targets, and every
supported runtime once signed SkidLLVM release archives are available.
