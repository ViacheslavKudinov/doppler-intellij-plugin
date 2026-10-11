<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Doppler-plugin Changelog

## [1.1.0](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/compare/v1.0.1...v1.1.0) (2026-10-11)


### Features

* **editor:** protect unsaved changes and improve config navigation ([#31](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/31)) ([3593755](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/3593755b36edea07f41ed196b7f61fea2926e04c))


### Maintenance

* **workflows:** enhance build and publish workflows ([#29](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/29)) ([bd8a1af](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/bd8a1af63b7375bba2871fe5a783c0328dfe2683))

## [1.0.1](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/compare/v1.0.0...v1.0.1) (2026-10-11)


### Bug Fixes

* update tree state management, robust refresh logic, and improved user experience ([#28](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/28)) ([c42e643](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/c42e643c5aeda2f5690b9edf85bdad27c3af8f79))


### Maintenance

* Add provenance generation ([#25](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/25)) ([6adb4b0](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/6adb4b04d0970018b7270cdc05e33fa442df9966))
* Change publish login and skip snapshot  ([#24](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/24)) ([65224cc](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/65224ccf0ed26d8e5ed02b093517d9027a324891))
* **main:** release 1.0.1-SNAPSHOT ([#22](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/22)) ([acfe18f](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/acfe18fd7955d899e0cc207304831f7dfb49847b))
* **workflows:** clean up build and publish workflows by removing commented-out steps and unnecessary permissions ([#26](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/26)) ([bacdf74](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/bacdf74f2068ac778cfccd8fa8ac7daf70c1c841))
* **workflows:** enhance publish workflow to include SBOM generation and attestation ([#27](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/27)) ([29ea086](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/29ea0862f9e200c478d04f00e9d4106eee77204b))

## 1.0.0 (2026-10-11)


### Features

* add Apache License 2.0 to the repository ([924cfe7](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/924cfe7b68edfc61ca6cbf3c882e4981ab90877b))
* add build and security analysis workflows for GitHub Actions ([141217d](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/141217d2042081aee76336bf4f176f159be32677))
* add GitHub Actions security analysis workflow with zizmor ([df9c952](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/df9c952d6f8c9cdf5b8acbbe2c6410a836a125f5))
* add GitHub Actions security analysis workflow with zizmor ([#3](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/3)) ([ba1d15a](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/ba1d15a762b814d3326d18d990b44116ef61276c))
* add issue templates for bug reports and feature requests, and configure dependabot ([30fc6bb](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/30fc6bba8a040a75de80d93a1e60b108e35313b4))
* add issue templates for bug reports and feature requests, and configure dependabot ([179b8e1](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/179b8e11911ca2aa43ee717bae8cd5daedc17f5a))
* add runner hardening step to build and zizmor workflows ([4607de0](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/4607de0db97f2f1f0f4ef9ac77938a0c96a74e67))
* add runner hardening step to build and zizmor workflows ([#5](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/5)) ([07cd506](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/07cd506dfe6d25dc9a59b3010f869f1343800f93))
* enhance build workflow with runner hardening and update test script for argument handling ([16625b6](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/16625b65ecc041b432ebf0d48ae49380dfe6d1be))
* enhance terminal session management and improve executable path resolution ([eb064c7](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/eb064c790dc4108e540321377d9ad9678c700c85))
* update GitHub Actions workflow to disable credential persistence for fetch steps ([656b7f3](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/656b7f33be94620a3593b7f8acdf44d8cff1c83f))
* update Gradle setup to conditionally enable read-only cache for non-main branches ([78b8305](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/78b8305d51b3133fd8c8c1fae7a269984d0af5c4))


### Bug Fixes

* update factory class references in plugin.xml and streamline terminal session creation ([4d2cd00](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/4d2cd000b9db5a6c71af22053a755a58926c29ba))


### Maintenance

* **actions:** Change java-kotlin build mode to manual ([1208bab](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/1208babdf3cb0948b93f407f6f1ebf8f2db854b9))
* **actions:** Change java-kotlin build mode to manual ([810d87f](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/810d87fd4fe6aeb74873209d8705ee58f013c8eb))
* **actions:** Change java-kotlin build mode to manual ([#9](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/9)) ([dcf65fd](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/dcf65fd36fd7ec9cdd88a38add1a8ab52081d1c3))
* **actions:** increase JVM memory for manual build steps in CodeQL workflow ([9775926](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/97759267adeea3a8c94b96eb22c7a7e0c23fd9cf))
* **actions:** increase JVM memory for manual build steps in CodeQL workflow ([3847eef](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/3847eefa4d7a5406bdaa23270bafe8b98c198c02))
* Add back harder runner on scorecard workflow ([#16](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/16)) ([7d3022b](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/7d3022bd8fdb9aa374e78ecbb48d052d2ec7081a))
* add copilot instructions for commit messages and PR titles ([fc721a6](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/fc721a624715603c24c20ffdb778b0afa55c3de5))
* add OpenSSF Scorecard badge to README ([#12](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/12)) ([cc19cd8](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/cc19cd812cf012ed9b264ea2eb1a636565b5e59e))
* **deps:** add cooldown settings for Gradle and GitHub Actions updates ([b026b7e](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/b026b7e890d92cd82f551b68276badb9dd06502c))
* **deps:** bump the minor-patch-updates group with 2 updates ([16a6e96](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/16a6e964915b81a72627e6ce1d3f6b85d5190c9b))
* **deps:** bump the minor-patch-updates group with 2 updates ([#8](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/8)) ([4428b40](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/4428b4080b8c0089434c9212d0f7fa18bb0e2d0c))
* **deps:** fix formatting in dependabot configuration ([3aa973a](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/3aa973a8fe2646206bd0d1997fd67876a9d62aa5))
* **deps:** remove specific cooldown days for version updates in dependabot configuration ([21fdda9](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/21fdda9b7da03ddd59ec2804667aaa83cecd0eda))
* **deps:** update default cooldown days in dependabot configuration to 7 ([f42173c](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/f42173c9f7c14f3ea0795e467d7e87f453f90a38))
* **deps:** update dependabot configuration for Gradle and GitHub Actions ([9293b72](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/9293b727f6316008c53ec97cacf7ed8b1fecc5af))
* **deps:** update dependabot configuration for Gradle and GitHub Actions ([#7](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/7)) ([528e197](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/528e197c1781744cb5c93d4d0457856fddbc27ea))
* **deps:** update workflow to ignore specific paths for push and pull request triggers ([afe1b5f](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/afe1b5f872ea9860256ea9977e3cadbc1be5b61a))
* Maintenance/codeowners ([#11](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/11)) ([b89f7e7](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/b89f7e77e08f628da5a7b8efe719391cd88d41cd))
* tune scorecard link ([#13](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/13)) ([5800d17](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/5800d17fd6c18b1aa4f248bddd02f69514d567ec))
* update changelog sections in release configuration ([#21](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/21)) ([4335fa3](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/4335fa3e3dca6b941cfdb23906e9919102620fb6))
* **workflows:** add CODEOWNERS and copilot instructions to workflow triggers ([10151e2](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/10151e2e10ede703e671584310a58839bfba0108))
* **workflows:** add signing and publishing steps to GitHub Actions workflow ([#20](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/20)) ([706f471](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/706f47190e8378ef27b8dca840b4fe28970ca113))
* **workflows:** comment out hardened runner step in scorecard workflow ([#14](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/14)) ([b213865](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/b213865094aac0ccf42b2c6c06325c9c35082680))
* **workflows:** increase JVM memory for build steps in build.yaml and codeql.yml ([617aca6](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/617aca64ee4cbd381845507bfef850afa9450415))
* **workflows:** refactor CI workflows and add new release and scorecard configurations ([#17](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/17)) ([e2cff0e](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/e2cff0e0c639d100ac3e374e1a96118da814e8cb))
* **workflows:** rename checkout steps for clarity in build and scorecard workflows ([#18](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues/18)) ([ff634e1](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/ff634e157ef522794f5924dd4b83752abc947203))
* **workflows:** update build.yaml to ignore additional workflow files and add permissions to codeql.yml ([df53a59](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/df53a599812c59f0ebfefdf9c459963dcbec82bf))
* **workflows:** update CodeQL and zizmor workflows for consistency and timezone configuration ([b988bda](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/b988bda5886c744245a0be60c574c23888229594))
* **workflows:** Update GitHub Actions workflow for scorecard ([75556a4](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/commit/75556a4e537f638a32e744f9dde4afcee146fa8b))

## [Unreleased]
