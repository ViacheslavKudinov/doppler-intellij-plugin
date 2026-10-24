# Injection Examples

These examples cover every supported launch integration. They check fixed, non-secret values
and print only a pass/fail result, never environment values. No example needs application dependencies.
Maven downloads its build plugins on the first run; Docker may need to download Alpine.

| Integration | Example |
| --- | --- |
| Java Application and Maven | [Java/Maven project](java-maven/pom.xml) |
| Python | [Python script](python/verify.py) |
| Node.js and npm | [Node script](node/verify.cjs) and [npm tasks](node/package.json) |
| Gradle | [Gradle verification tasks](gradle/build.gradle.kts) |
| Docker image and Dockerfile | [Dockerfile](docker/Dockerfile) |
| Docker Compose | [Compose file](docker/compose.yaml) |

## Common Setup

1. Install the Doppler plugin and the corresponding language/build/Docker integration in your
   JetBrains IDE. Install and sign in to the Doppler CLI.
2. Create a disposable Doppler project/config containing only these synthetic test entries:

   | Secret name | Test value |
   | --- | --- |
   | `EXAMPLE_MESSAGE` | `doppler-example` |
   | `EXAMPLE_NUMBER` | `42` |

   Create them in Doppler, not in an `.env` file. Do not use a real application's secrets.
3. Open the selected example folder as an IDE project. Select the test project/config in
   **Settings | Tools | Doppler | Folder Setup**. Enable **Automatically inject Doppler secrets
   into Run and Debug** and choose the `none` name transformer.
4. Launch the example using an IDE Run/Debug configuration as described below. A successful
   check prints `PASS` and exits with code zero. Missing or incorrect variables fail the check.

Defaults and injection approval are local to each IDE project; repeat setup when opening a
different example. Leave example variables out of the run configuration's explicit environment:
explicit entries take precedence over Doppler injection.

## Java And Maven

Open `java-maven/pom.xml` as a Maven project and select JDK 17 or newer.
Both launch paths use the same [main class](java-maven/src/main/java/com/vk/doppler/examples/InjectionExample.java).

- **Java Application:** Run or Debug `InjectionExample.main` using the editor gutter, or create
  an **Application** configuration with main class `com.vk.doppler.examples.InjectionExample`
  and the example module's classpath.
- **Maven:** create a **Maven** Run/Debug configuration with working directory `java-maven`
  and command line `compile exec:java`. Injection reaches Maven's JVM, which runs the verifier.

## Python

Open `python` as an IDE project and select a Python 3 interpreter. Create a **Python** Run/Debug
configuration with script path `verify.py`, then Run or Debug it. No packages or virtual environment
are required by the example.

## Node.js And npm

Open `node` as an IDE project and select a supported Node.js LTS interpreter.
No `npm install` step is needed.

- **Node.js:** create a **Node.js** configuration with JavaScript file `verify.cjs`, then Run or Debug it.
- **npm:** create an **npm** configuration using `package.json`, command `run`, and script `verify`.
  Run or Debug the configuration to exercise the npm-based launch path.

## Gradle

Open `gradle` as an IDE project and load it as a Gradle project. Use JDK 17 or newer and a
compatible local Gradle installation. If you need a wrapper, run
`./gradlew -p examples/gradle wrapper` from the repository root, then reload the example using
its generated wrapper. Run `verifyInjection` from the Gradle tool window or a Gradle Run/Debug
configuration. Project sync itself does not inject secrets.

## Docker Image And Dockerfile

Open `docker` as an IDE project. Docker Engine and the IDE's Docker integration must be available.
The image runs the [same verifier](docker/verify.sh) as the Compose example, without baking
environment values into the image.

- **Dockerfile:** create a **Dockerfile** run configuration using `Dockerfile`, with the example's
  `docker` folder as the build context, then Run it.
- **Docker image:** build the example once from the repository root with
  `docker build -t doppler-injection-example examples/docker`. Create a **Docker Image** run
  configuration using image `doppler-injection-example`, then Run it.

Do not add build arguments or image `ENV` instructions for the test values. Injection happens
at container launch, not during image building. The verifier exits immediately; container debugging
depends on the IDE's native Docker debugger support and is not demonstrated by this shell image.

## Docker Compose

Open `docker` as an IDE project. Create a **Docker Compose** run configuration using
`compose.yaml`, select the `verify` service, and Run it. Docker Engine and Docker Compose must be
available. The service exits successfully when both variables reach the container.

Compose does not automatically forward all process variables into a container. Declare the names
each service needs through its `environment` entries; secret values remain outside the YAML file:

```yaml
services:
  api:
    image: my-api
    environment:
      DATABASE_URL: ${DATABASE_URL}
```

Custom mappings do not rewrite your Compose file: if `DATABASE_URL` is mapped to
`APP_DATABASE_URL`, declare `APP_DATABASE_URL: ${APP_DATABASE_URL:?APP_DATABASE_URL is required}`
under the service's `environment` and remove any old `DATABASE_URL` entry you no longer need.
A key explicitly declared in Compose can still appear under its old name. Recreate the container
after changing its environment. The runnable examples declare both test variables explicitly;
see [Custom Mappings](#custom-mappings) for the mapped version.

## Custom Mappings

Choose the `custom` transformer in the example project's Doppler Run and Debug settings and add:

| Current secret name | New secret name |
| --- | --- |
| `EXAMPLE_MESSAGE` | `APP_MESSAGE` |
| `EXAMPLE_NUMBER` | `APP_NUMBER` |

Launch the matching custom check:

| Integration | Run configuration change |
| --- | --- |
| Java Application | Set program arguments to `custom` |
| Maven | Use command line `compile exec:java -Dexample.mode=custom` |
| Python | Set script parameters to `custom` |
| Node.js | Set application parameters to `custom` |
| npm | Select script `verify:custom` |
| Gradle | Run task `verifyCustomInjection` |
| Docker image / Dockerfile | Override the container command with `custom`, keeping the image entrypoint |
| Docker Compose | Use only [compose.custom.yaml](docker/compose.custom.yaml), not both files as overrides |

Custom checks require both mapped names and reject either original name, even if its value is
empty. Remove explicit or inherited copies of the original names before testing. A mapping does
not rewrite Compose YAML: the custom Compose file declares the new names explicitly. Recreate
containers after changing mappings or environment settings.

## Cleanup And Limits

The IDE must launch these examples for the plugin to inject values. Running them directly in a
terminal does not invoke the plugin and should fail unless another tool supplies the environment.
Checking with synthetic variables in a terminal tests the verifier, not IDE injection.

Stop and remove containers created by the Docker Image/Dockerfile configurations using the IDE's
Services tool window. For Compose, run `docker compose down` from `examples/docker`, or
`docker compose -f compose.custom.yaml down` for the custom example. If no longer needed, remove
the locally built image with `docker image rm doppler-injection-example`.

Docker retains container environments by design. Use only these synthetic values, remove the
containers after testing, and never print or dump real secrets. Delete the disposable Doppler
project/config when finished.