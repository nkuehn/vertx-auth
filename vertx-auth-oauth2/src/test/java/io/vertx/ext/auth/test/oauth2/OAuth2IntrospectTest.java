package io.vertx.ext.auth.test.oauth2;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.oauth2.AccessToken;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2ClientOptions;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.test.core.VertxTestBase;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.CountDownLatch;

import static io.vertx.ext.auth.oauth2.impl.OAuth2API.*;

public class OAuth2IntrospectTest extends VertxTestBase {

  // according to RFC
  private static final JsonObject fixtureIntrospect = new JsonObject(
    "{" +
      "  \"active\": true," +
      "  \"scope\": \"scopeA scopeB\"," +
      "  \"client_id\": \"client-id\"," +
      "  \"username\": \"username\"," +
      "  \"token_type\": \"bearer\"," +
      "  \"exp\": 99999999999," +
      "  \"iat\": 7200," +
      "  \"nbf\": 7200" +
      "}");

  // according to Google
  private static final JsonObject fixtureGoogle = new JsonObject(
    "{" +
      "  \"audience\": \"8819981768.apps.googleusercontent.com\"," +
      "  \"user_id\": \"123456789\"," +
      "  \"scope\": \"profile email\"," +
      "  \"expires_in\": 436" +
      "}");

  // according to Keycloak
  private static final JsonObject fixtureKeycloak = new JsonObject(
    "{" +
      "  \"active\": true," +
      "  \"exp\": 99999999999," +
      "  \"iat\": 1465313839," +
      "  \"aud\": \"hello-world-authz-service\",\n" +
      "  \"nbf\": 0" +
      "}");

  // a valid JWT token
  private static final String token = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJhdXRob3JpemF0aW9uIjp7InBlcm1pc3Npb25zIjpbeyJyZXNvdXJjZV9zZXRfaWQiOiJkMmZlOTg0My02NDYyLTRiZmMtYmFiYS1iNTc4N2JiNmUwZTciLCJyZXNvdXJjZV9zZXRfbmFtZSI6IkhlbGxvIFdvcmxkIFJlc291cmNlIn1dfSwianRpIjoiZDYxMDlhMDktNzhmZC00OTk4LWJmODktOTU3MzBkZmQwODkyLTE0NjQ5MDY2Nzk0MDUiLCJleHAiOjk5OTk5OTk5OTksIm5iZiI6MCwiaWF0IjoxNDY0OTA2NjcxLCJzdWIiOiJmMTg4OGY0ZC01MTcyLTQzNTktYmUwYy1hZjMzODUwNWQ4NmMiLCJ0eXAiOiJrY19ldHQiLCJhenAiOiJoZWxsby13b3JsZC1hdXRoei1zZXJ2aWNlIn0";
  private static final JsonObject oauthIntrospect = new JsonObject()
    .put("token", token);

  // a token that is not a JWT (the example from the RFC)
  private static final String opaqueToken = "mF_9.B5f-4.1JqM";
  private static final JsonObject opaqueOauthIntrospect = new JsonObject()
    .put("token", opaqueToken);


  private OAuth2Auth oauth2;
  private OAuth2Auth opaqueOauth2;
  private HttpServer server;
  private JsonObject config;
  private JsonObject fixture;

  private final OAuth2ClientOptions oauthConfig = new OAuth2ClientOptions()
    .setClientID("client-id")
    .setClientSecret("client-secret")
    .setSite("http://localhost:8080")
    .setIntrospectionPath("/oauth/introspect");

  @Override
  public void setUp() throws Exception {
    super.setUp();
    oauth2 = OAuth2Auth.create(vertx, OAuth2FlowType.AUTH_CODE, oauthConfig);
    opaqueOauth2 = OAuth2Auth.create(vertx, OAuth2FlowType.AUTH_CODE, oauthConfig.setJWTToken(false));

    final CountDownLatch latch = new CountDownLatch(1);

    server = vertx.createHttpServer().requestHandler(req -> {
      if (req.method() == HttpMethod.POST && "/oauth/introspect".equals(req.path())) {
        req.setExpectMultipart(true).bodyHandler(buffer -> {
          try {
            JsonObject body = queryToJSON(buffer.toString());
            assertEquals(config.getString("token"), body.getString("token"));
            // conditional test for token_type_hint
            if (config.containsKey("token_type_hint")) {
              assertEquals(config.getString("token_type_hint"), body.getString("token_type_hint"));
            }
          } catch (UnsupportedEncodingException e) {
            fail(e);
          }
          req.response().putHeader("Content-Type", "application/json").end(fixture.encode());
        });
      } else if (req.method() == HttpMethod.POST && "/oauth/tokeninfo".equals(req.path())) {
          req.setExpectMultipart(true).bodyHandler(buffer -> {
            try {
              assertEquals(config, queryToJSON(buffer.toString()));
            } catch (UnsupportedEncodingException e) {
              fail(e);
            }
            req.response().putHeader("Content-Type", "application/json").end(fixture.encode());
          });
      } else {
        req.response().setStatusCode(400).end();
      }
    }).listen(8080, ready -> {
      if (ready.failed()) {
        throw new RuntimeException(ready.cause());
      }
      // ready
      latch.countDown();
    });

    latch.await();
  }

  @Override
  public void tearDown() throws Exception {
    server.close();
    super.tearDown();
  }

  @Test
  public void introspectAccessToken() {
    config = oauthIntrospect;
    fixture = fixtureIntrospect;
    oauth2.introspectToken(token, res -> {
      if (res.failed()) {
        fail(res.cause().getMessage());
      } else {
        AccessToken token = res.result();
        assertNotNull(token);
        JsonObject principal = token.principal();

        // clean time specific value
        principal.remove("expires_at");
        principal.remove("access_token");

        final JsonObject assertion = fixtureIntrospect.copy();

        assertEquals(assertion.getMap(), principal.getMap());

        token.isAuthorized("scopeB", res0 -> {
          if (res0.failed()) {
            fail(res0.cause().getMessage());
          } else {
            if (res0.result()) {
              testComplete();
            } else {
              fail("Should be allowed");
            }
          }
        });
      }
    });
    await();
  }

  @Test
  public void introspectOpaqueAccessToken() {
    config = opaqueOauthIntrospect;
    fixture = fixtureIntrospect;
    opaqueOauth2.introspectToken(opaqueToken, res -> {
      if (res.failed()) {
        fail(res.cause().getMessage());
      } else {
        AccessToken token = res.result();
        assertNotNull(token);
        JsonObject principal = token.principal();

        // clean time specific value
        principal.remove("expires_at");
        principal.remove("access_token");

        final JsonObject assertion = fixtureIntrospect.copy();

        assertEquals(assertion.getMap(), principal.getMap());

        token.isAuthorized("scopeB", res0 -> {
          if (res0.failed()) {
            fail(res0.cause().getMessage());
          } else {
            if (res0.result()) {
              testComplete();
            } else {
              fail("Should be allowed");
            }
          }
        });
      }
    });
    await();
  }

  @Test
  public void introspectAccessTokenGoogleWay() {
    config = oauthIntrospect;
    fixture = fixtureGoogle;
    oauth2.introspectToken(token, res -> {
      if (res.failed()) {
        fail(res.cause().getMessage());
      } else {
        AccessToken token = res.result();
        assertNotNull(token);
        // make a copy because later we need to original data
        JsonObject principal = token.principal().copy();

        // clean time specific value
        principal.remove("expires_at");
        principal.remove("access_token");

        assertEquals(fixtureGoogle.getMap(), principal.getMap());

        token.isAuthorized("profile", res0 -> {
          if (res0.failed()) {
            fail(res0.cause().getMessage());
          } else {
            if (res0.result()) {
              // Issue #142

              // the test is a replay of the same test so all checks have
              // been done above.

              // the replay shows that the api can be used from the user object
              // directly too
              token.introspect(v -> {
                if (v.failed()) {
                  fail(v.cause());
                } else {
                  testComplete();
                }
              });
            } else {
              fail("Should be allowed");
            }
          }
        });
      }
    });
    await();
  }

  @Test
  public void introspectAccessTokenKeyCloakWay() {
    config = oauthIntrospect;
    fixture = fixtureKeycloak;
    oauth2.introspectToken(token, res -> {
      if (res.failed()) {
        fail(res.cause().getMessage());
      } else {
        AccessToken token = res.result();
        assertNotNull(token);
        JsonObject principal = token.principal();

        assertTrue(principal.getBoolean("active"));
        testComplete();
      }
    });
    await();
  }
}
