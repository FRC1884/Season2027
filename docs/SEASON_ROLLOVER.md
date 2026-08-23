# Season2026 → Season2027 Rollover

Season2027 is based on the Season2026 repository architecture, but starts without a 2027 robot design.

## Carried forward

- Java 17 / GradleRIO project structure and Team 1884 RoboRIO configuration
- current supported 2026 WPILib baseline until official 2027 releases are published
- CI / Spotless build conventions
- reusable input abstractions and generic math/geometry utilities
- current vendor baseline needed for reusable infrastructure
- DataLog/WPILib team tooling configuration
- Robotics Agentic Development Harness governance surfaces
- Codex Automated Reviewer agent/skill
- CODEOWNERS, PR template, protected-path policy, monitoring and school-demo documentation

All Java code carried forward is under:

```text
org.Griffins1884.frc2027
```

There are no `org.Griffins1884.frc2026` package references in the Season2027 source tree.

## Intentionally removed for the new season

The following Season2026 content is not rolled forward because it represents a mechanism, completed robot architecture, or 2026 game-specific configuration:

- `mechanisms/**`
- `subsystems/**` including swerve, intake, indexer, shooter, turret, LEDs, vision and superstructure implementations
- mechanism and subsystem commands
- mechanism/subsystem-specific tests
- 2026 autonomous routines and game-field coordinates
- 2026 AprilTag/field layouts
- 2026 operator-board state/data tied to the completed robot
- 2026 robot composition/state-machine logic

`Robot.java` and `RobotContainer.java` are replaced with clean 2027 shells so new mechanisms can be introduced through governed student PRs.

## 2027 dependency upgrade

WPILib and FRC vendor 2027 releases are not available yet. Current 2026 dependency metadata is therefore a temporary compatibility baseline, not a claim that 2026 robot code is being reused. When the official 2027 toolchain ships, update GradleRIO, WPILib project metadata, wrapper files, and vendor JSONs in one protected infrastructure PR before student mechanism work begins.
