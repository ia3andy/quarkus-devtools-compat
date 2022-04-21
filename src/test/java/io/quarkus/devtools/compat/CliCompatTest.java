package io.quarkus.devtools.compat;


import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.paukov.combinatorics3.Generator;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.aFileWithSize;

public class CliCompatTest {

    public static final Path VERIFIED_COMBINATIONS_FILE = Path.of("./verified-combinations.json");
    private static WebClient client = WebClient.create(Vertx.vertx());
    private static Set<Combination> verifiedCombinations;

    @BeforeAll
    public static void beforeAll() throws IOException {
        verifiedCombinations = new HashSet<>(readVerifiedCombinations());
        System.out.println(verifiedCombinations.size() + " combinations were already verified");
    }

    public void storeCombinations() throws IOException {
        System.out.println(verifiedCombinations.size() + " combinations are now verified");
        Files.writeString(VERIFIED_COMBINATIONS_FILE, new JsonArray(verifiedCombinations.stream().map(Combination::toMap).collect(Collectors.toList())).encode());
    }

    @TestFactory
    Stream<DynamicTest> testCli(@TempDir Path tempDir) throws IOException {

        final List<String> versions = client.getAbs("https://registry.quarkus.io/client/platforms/all")
            .send()
            .onItem().transform(HttpResponse::bodyAsJsonObject)
            .onItem().transform(CliCompatTest::extractVersions)
            .await().indefinitely();

        return generateTests(tempDir, versions);
    }

    private static Set<Combination> readVerifiedCombinations() throws IOException {
        final Path path = VERIFIED_COMBINATIONS_FILE;
        if (!Files.isRegularFile(path)) {
           return Set.of();
        }
        return new JsonArray(Files.readString(path)).stream()
            .map(o -> (JsonObject) o)
            .map(j -> new Combination(j.getString("platform-version"), j.getString("cli-version")))
            .collect(Collectors.toSet());
    }

    private static List<String> extractVersions(JsonObject o) {
        return o.getJsonArray("platforms").stream()
            .map(m -> (JsonObject) m)
            .flatMap(j -> j.getJsonArray("streams").stream())
            .map(m -> (JsonObject) m)
            .flatMap(j -> j.getJsonArray("releases").stream())
            .map(m -> (JsonObject) m)
            .map(j -> j.getString("version"))
            .filter(v -> !v.contains("CR"))
            .collect(Collectors.toList());
    }

    private Stream<DynamicTest> generateTests(Path tempDir, List<String> versions) {

        List<Combination> tests = Generator.cartesianProduct(versions, versions).stream().map(i ->
                new Combination(i.get(0), i.get(1))).collect(Collectors.toList());

        final Stream<DynamicTest> dynamicTestStream = tests.stream()
            .map(c -> DynamicTest.dynamicTest(
                "create-cli:" + c.cliVersion() + "-platform:" + c.platformVersion(),
                () -> {
                    testCLI(tempDir.resolve("cli_" + c.cliVersion + "-platform_" + c.platformVersion), c);
                }
            ));

        return dynamicTestStream;
    }

    public void testCLI(Path tempDir, Combination combination) throws IOException, InterruptedException, TimeoutException {

        tempDir.toFile().mkdirs();

        String trust = jbang(tempDir, "trust", "add", "https://repo1.maven.org/maven2/io/quarkus/");

        assertThat(trust, matchesPattern("(?s).*Adding .https://repo1.maven.org/maven2/io/quarkus/. to .*/trusted-sources.json.*"));

        String appname = "qs-" + combination.cliVersion.replace(".","_");
        String output = jbang(tempDir, "alias", "add", "-f", ".", "--name="+appname, "https://repo1.maven.org/maven2/io/quarkus/quarkus-cli/" + combination.cliVersion + "/quarkus-cli-"+combination.cliVersion+"-runner.jar");

        assertThat(output, matchesPattern(".jbang. Alias .* added .*\n"));

        String createResult = jbang(tempDir,appname, "create", "-P", "io.quarkus.platform::" + combination.platformVersion, "demoapp");

        assertThat(tempDir.toFile(), aFileWithSize(greaterThan(1L)));

        int result = run(tempDir.resolve("demoapp"), "quarkus", "build")
            .redirectOutputAlsoTo(new LogOutputStream() {
                @Override
                protected void processLine(String s) {
                    assertThat(s, not(matchesPattern("(?i)ERROR")));
                }
            }).execute().getExitValue();

        assertThat(result, equalTo(0));
        verifiedCombinations.add(combination);
        storeCombinations();
    }

    String jbang(Path workingDir, String... args) throws IOException, InterruptedException, TimeoutException {
        List<String> realArgs = new ArrayList<>();
        realArgs.add("jbang");
        realArgs.addAll(Arrays.asList(args));

        return run(workingDir, realArgs.toArray(new String[0])).execute().outputUTF8();
    }

    ProcessExecutor run(Path workingDir, String... args) throws IOException, InterruptedException, TimeoutException {
        List<String> realArgs = new ArrayList<>();
        realArgs.addAll(Arrays.asList(args));

        System.out.println("run: " + String.join(" ", realArgs));
        return new ProcessExecutor().command(realArgs)
            .directory(workingDir.toFile())
            .redirectOutputAlsoTo(System.out)
            .exitValue(0)
            .readOutput(true);

    }

    static record Combination(String platformVersion, String cliVersion) {

        Map<String, String> toMap() {
            return Map.of("platform-version", platformVersion, "cli-version", cliVersion);
        }
    }
}
