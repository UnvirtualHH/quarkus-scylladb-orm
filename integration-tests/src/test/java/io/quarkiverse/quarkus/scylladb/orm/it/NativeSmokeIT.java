package io.quarkiverse.quarkus.scylladb.orm.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkiverse.quarkus.scylladb.orm.it.util.RunnableOnThisHost;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Drives the packaged binary over HTTP. Runs only when {@code -Dnative} is set, via the
 * {@code native-image} profile that flips {@code skipITs}.
 * <p>
 * Until now the native build was never verified beyond "it compiled": there were no
 * {@code *IT} classes at all, so failsafe had nothing to execute. A native image that
 * builds can still fail on first use — a missing reflection registration or a class
 * initialized at build time that should not have been shows up when the code runs. Every
 * path here is one the extension generates code for.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@ExtendWith(RunnableOnThisHost.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NativeSmokeIT {

    private static String profileId;
    private static String deviceId;
    private static String eventId;

    @Test
    @Order(1)
    void theApplicationStarts() {
        // If the image fails to initialize the driver or the CDI beans, this is where it
        // shows - the JVM test suite can never reach that failure mode.
        given().when().post("/smoke/profile").then().statusCode(200);
    }

    @Test
    @Order(2)
    void enumsCollectionsAndConvertersSurviveNativeCompilation() {
        profileId = given().when().post("/smoke/profile").then().statusCode(200)
                .extract().asString().trim();

        given().when().get("/smoke/profile/" + profileId).then()
                .statusCode(200)
                .body(equalTo("SUSPENDED|ENTERPRISE|alpha,beta|3|de|dark:14"));
    }

    @Test
    @Order(3)
    void theReactiveRepositoryWorksNativelyToo() {
        given().when().get("/smoke/profile/" + profileId + "/reactive").then()
                .statusCode(200)
                .body(equalTo("ENTERPRISE"));
    }

    @Test
    @Order(4)
    void compositeKeysAndTemporalColumnsRoundTrip() {
        String created = given().when().post("/smoke/event").then().statusCode(200)
                .extract().asString().trim();
        deviceId = created.split("/")[0];
        eventId = created.split("/")[1];

        given().when().get("/smoke/event/" + deviceId + "/" + eventId).then()
                .statusCode(200)
                .body(equalTo("2026-08-04|13.37"));
    }

    @Test
    @Order(5)
    void theBlockingRepositoryRefusesToRunOnTheEventLoop() {
        // The only end-to-end check of that guard: it needs a real Vert.x event loop,
        // which no @QuarkusTest method runs on.
        given().when().get("/smoke/event-loop-guard").then()
                .statusCode(200)
                .body(equalTo("guarded"));
    }

    @Test
    @Order(6)
    void generatedQueryMethodsWorkNatively() {
        given().when().get("/smoke/event/" + deviceId + "/limited").then()
                .statusCode(200)
                .body(not(equalTo("0")));
    }
}
