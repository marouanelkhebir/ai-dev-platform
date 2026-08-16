# Commands — <service name>

Copy to `.ai/commands.md`. The agents can only run executables from the platform allowlist
(`git`, `mvn`/`./mvnw`, `java`, `ls`, `cat`, `grep`, `find`, `test`), so list only commands that fit.

## Build

```
./mvnw -B -ntp test          # unit tests
./mvnw -B -ntp verify        # full build including integration tests
./mvnw -B -ntp test-compile  # compile only, fastest feedback loop
```

## Focused runs

```
./mvnw -B -ntp test -Dtest=FeeSuspensionServiceTest
./mvnw -B -ntp test -Dtest=FeeSuspensionServiceTest#shouldSuspendActiveFee
```

## Notes

- `<e.g. integration tests need Docker; they are skipped without it>`
- `<e.g. the full verify takes about 6 minutes>`
