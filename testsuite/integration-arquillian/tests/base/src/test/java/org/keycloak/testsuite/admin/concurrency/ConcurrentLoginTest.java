/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.admin.concurrency;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.testsuite.Retry;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.util.ClientBuilder;
import org.keycloak.testsuite.util.OAuthClient;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.client.CookieStore;
import org.apache.http.impl.client.BasicCookieStore;
import org.hamcrest.Matchers;



/**
 * @author <a href="mailto:vramik@redhat.com">Vlastislav Ramik</a>
 */
public class ConcurrentLoginTest extends AbstractConcurrencyTest {
    
    protected static final int DEFAULT_THREADS = 4;
    protected static final int CLIENTS_PER_THREAD = 30;
    protected static final int DEFAULT_CLIENTS_COUNT = CLIENTS_PER_THREAD * DEFAULT_THREADS;

    @Before
    public void beforeTest() {
        createClients();
    }

    protected void createClients() {
        final ClientsResource clients = adminClient.realm(REALM_NAME).clients();
        for (int i = 0; i < DEFAULT_CLIENTS_COUNT; i++) {
            ClientRepresentation client = ClientBuilder.create()
              .clientId("client" + i)
              .directAccessGrants()
              .redirectUris("http://localhost:8180/auth/realms/master/app/*")
              .addWebOrigin("http://localhost:8180")
              .secret("password")
              .build();

            Response create = clients.create(client);
            String clientId = ApiUtil.getCreatedId(create);
            create.close();
            getCleanup(REALM_NAME).addClientUuid(clientId);
            log.debugf("created %s [uuid=%s]", client.getClientId(), clientId);
        }
        log.debug("clients created");
    }

    @Test
    public void concurrentLoginSingleUser() throws Throwable {
        log.info("*********************************************");
        long start = System.currentTimeMillis();

        AtomicReference<String> userSessionId = new AtomicReference<>();
        LoginTask loginTask = null;

        try (CloseableHttpClient httpClient = HttpClientBuilder.create().setRedirectStrategy(new LaxRedirectStrategy()).build()) {
            loginTask = new LoginTask(httpClient, userSessionId, 100, 1, false, Arrays.asList(
              createHttpClientContextForUser(httpClient, "test-user@localhost", "password")
            ));
            run(DEFAULT_THREADS, DEFAULT_CLIENTS_COUNT, loginTask);
            int clientSessionsCount = testingClient.testing().getClientSessionsCountInUserSession("test", userSessionId.get());
            Assert.assertEquals(1 + DEFAULT_CLIENTS_COUNT, clientSessionsCount);
        } finally {
            long end = System.currentTimeMillis() - start;
            log.infof("Statistics: %s", loginTask == null ? "??" : loginTask.getHistogram());
            log.info("concurrentLoginSingleUser took " + (end/1000) + "s");
            log.info("*********************************************");
        }
    }

    protected HttpClientContext createHttpClientContextForUser(final CloseableHttpClient httpClient, String userName, String password) throws IOException {
        final HttpClientContext context = HttpClientContext.create();
        CookieStore cookieStore = new BasicCookieStore();
        context.setCookieStore(cookieStore);
        HttpUriRequest request = handleLogin(getPageContent(oauth.getLoginFormUrl(), httpClient, context), userName, password);
        log.debug("Executing login request");
        Assert.assertTrue(parseAndCloseResponse(httpClient.execute(request, context)).contains("<title>AUTH_RESPONSE</title>"));
        return context;
    }

    @Test
    public void concurrentLoginSingleUserSingleClient() throws Throwable {
        log.info("*********************************************");
        long start = System.currentTimeMillis();

        AtomicReference<String> userSessionId = new AtomicReference<>();
        LoginTask loginTask = null;

        try (CloseableHttpClient httpClient = HttpClientBuilder.create().setRedirectStrategy(new LaxRedirectStrategy()).build()) {
            loginTask = new LoginTask(httpClient, userSessionId, 100, 1, true, Arrays.asList(
                    createHttpClientContextForUser(httpClient, "test-user@localhost", "password")
            ));
            run(DEFAULT_THREADS, DEFAULT_CLIENTS_COUNT, loginTask);
            int clientSessionsCount = testingClient.testing().getClientSessionsCountInUserSession("test", userSessionId.get());
            Assert.assertEquals(2, clientSessionsCount);
        } finally {
            long end = System.currentTimeMillis() - start;
            log.infof("Statistics: %s", loginTask == null ? "??" : loginTask.getHistogram());
            log.info("concurrentLoginSingleUserSingleClient took " + (end/1000) + "s");
            log.info("*********************************************");
        }
    }

    @Test
    public void concurrentLoginMultipleUsers() throws Throwable {
        log.info("*********************************************");
        long start = System.currentTimeMillis();

        AtomicReference<String> userSessionId = new AtomicReference<>();
        LoginTask loginTask = null;

        try (CloseableHttpClient httpClient = HttpClientBuilder.create().setRedirectStrategy(new LaxRedirectStrategy()).build()) {
            loginTask = new LoginTask(httpClient, userSessionId, 100, 1, false, Arrays.asList(
              createHttpClientContextForUser(httpClient, "test-user@localhost", "password"),
              createHttpClientContextForUser(httpClient, "john-doh@localhost", "password"),
              createHttpClientContextForUser(httpClient, "roleRichUser", "password")
            ));

            run(DEFAULT_THREADS, DEFAULT_CLIENTS_COUNT, loginTask);
            int clientSessionsCount = testingClient.testing().getClientSessionsCountInUserSession("test", userSessionId.get());
            Assert.assertEquals(1 + DEFAULT_CLIENTS_COUNT / 3 + (DEFAULT_CLIENTS_COUNT % 3 <= 0 ? 0 : 1), clientSessionsCount);
        } finally {
            long end = System.currentTimeMillis() - start;
            log.infof("Statistics: %s", loginTask == null ? "??" : loginTask.getHistogram());
            log.info("concurrentLoginMultipleUsers took " + (end/1000) + "s");
            log.info("*********************************************");
        }
    }


    @Test
    public void concurrentCodeReuseShouldFail() throws Throwable {
        log.info("*********************************************");
        long start = System.currentTimeMillis();


        for (int i=0 ; i<10 ; i++) {
            OAuthClient oauth1 = new OAuthClient();
            oauth1.init(adminClient, driver);
            oauth1.clientId("client0");

            OAuthClient.AuthorizationEndpointResponse resp = oauth1.doLogin("test-user@localhost", "password");
            String code = resp.getCode();
            Assert.assertNotNull(code);
            String codeURL = driver.getCurrentUrl();


            AtomicInteger codeToTokenSuccessCount = new AtomicInteger(0);
            AtomicInteger codeToTokenErrorsCount = new AtomicInteger(0);

            KeycloakRunnable codeToTokenTask = new KeycloakRunnable() {

                @Override
                public void run(int threadIndex, Keycloak keycloak, RealmResource realm) throws Throwable {
                    log.infof("Trying to execute codeURL: %s, threadIndex: %i", codeURL, threadIndex);

                    OAuthClient.AccessTokenResponse resp = oauth1.doAccessTokenRequest(code, "password");
                    if (resp.getAccessToken() != null && resp.getError() == null) {
                        codeToTokenSuccessCount.incrementAndGet();
                    } else if (resp.getAccessToken() == null && resp.getError() != null) {
                        codeToTokenErrorsCount.incrementAndGet();
                    }
                }

            };

            run(DEFAULT_THREADS, DEFAULT_THREADS, codeToTokenTask);

            oauth1.openLogout();

            Assert.assertEquals(1, codeToTokenSuccessCount.get());
            Assert.assertEquals(DEFAULT_THREADS - 1, codeToTokenErrorsCount.get());

            log.infof("Iteration %i passed successfully", i);
        }

        long end = System.currentTimeMillis() - start;
        log.info("concurrentCodeReuseShouldFail took " + (end/1000) + "s");
        log.info("*********************************************");

    }


    protected String getPageContent(String url, CloseableHttpClient httpClient, HttpClientContext context) throws IOException {
        HttpGet request = new HttpGet(url);

        request.setHeader("User-Agent", "Mozilla/5.0");
        request.setHeader("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setHeader("Accept-Language", "en-US,en;q=0.5");

        return parseAndCloseResponse(httpClient.execute(request, context));
    }

    protected String parseAndCloseResponse(CloseableHttpResponse response) {
        try {
            int responseCode = response.getStatusLine().getStatusCode();
            String resp = EntityUtils.toString(response.getEntity());

            if (responseCode != 200) {
                log.debugf("Response Code: %d, Body: %s", responseCode, resp);
            }
            return resp;
        } catch (IOException | UnsupportedOperationException ex) {
            throw new RuntimeException(ex);
        } finally {
            if (response != null) {
                EntityUtils.consumeQuietly(response.getEntity());
                try {
                    response.close();
                } catch (IOException ex) { }
            }
        }
    }

    protected HttpUriRequest handleLogin(String html, String username, String password) throws UnsupportedEncodingException {
        log.debug("Extracting form's data...");

        // Keycloak form id
        Element loginform = Jsoup.parse(html).getElementById("kc-form-login");
        String method = loginform.attr("method");
        String action = loginform.attr("action");

        List<NameValuePair> paramList = new ArrayList<>();

        for (Element inputElement : loginform.getElementsByTag("input")) {
            String key = inputElement.attr("name");

            if (key.equals("username")) {
                paramList.add(new BasicNameValuePair(key, username));
            } else if (key.equals("password")) {
                paramList.add(new BasicNameValuePair(key, password));
            }
        }

        boolean isPost = method != null && "post".equalsIgnoreCase(method);

        if (isPost) {
            HttpPost req = new HttpPost(action);

            UrlEncodedFormEntity formEntity;
            try {
                formEntity = new UrlEncodedFormEntity(paramList, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            req.setEntity(formEntity);

            return req;
        } else {
            throw new UnsupportedOperationException("not supported yet!");
        }
    }

    private static Map<String, String> getQueryFromUrl(String url) throws URISyntaxException {
        Map<String, String> m = new HashMap<>();
        List<NameValuePair> pairs = URLEncodedUtils.parse(new URI(url), "UTF-8");
        for (NameValuePair p : pairs) {
            m.put(p.getName(), p.getValue());
        }
        return m;
    }


    public class LoginTask implements KeycloakRunnable {

        private final AtomicInteger clientIndex = new AtomicInteger();
        private final ThreadLocal<OAuthClient> oauthClient = new ThreadLocal<OAuthClient>() {
                @Override
                protected OAuthClient initialValue() {
                    OAuthClient oauth1 = new OAuthClient();
                    oauth1.init(adminClient, driver);
                    return oauth1;
                }
            };

        private final CloseableHttpClient httpClient;
        private final AtomicReference<String> userSessionId;

        private final int retryDelayMs;
        private final int retryCount;
        private final AtomicInteger[] retryHistogram;
        private final AtomicInteger totalInvocations = new AtomicInteger();
        private final boolean sameClient;
        private final List<HttpClientContext> clientContexts;

        public LoginTask(CloseableHttpClient httpClient, AtomicReference<String> userSessionId, int retryDelayMs, int retryCount, boolean sameClient, List<HttpClientContext> clientContexts) {
            this.httpClient = httpClient;
            this.userSessionId = userSessionId;
            this.retryDelayMs = retryDelayMs;
            this.retryCount = retryCount;
            this.retryHistogram = new AtomicInteger[retryCount];
            for (int i = 0; i < retryHistogram.length; i ++) {
                retryHistogram[i] = new AtomicInteger();
            }
            this.sameClient = sameClient;
            this.clientContexts = clientContexts;
        }

        @Override
        public void run(int threadIndex, Keycloak keycloak, RealmResource realm) throws Throwable {
            int i = sameClient ? 0 : clientIndex.getAndIncrement();
            OAuthClient oauth1 = oauthClient.get();
            oauth1.clientId("client" + i);
            log.infof("%d [%s]: Accessing login page for %s", threadIndex, Thread.currentThread().getName(), oauth1.getClientId());

            final HttpClientContext templateContext = clientContexts.get(i % clientContexts.size());
            final HttpClientContext context = HttpClientContext.create();
            context.setCookieStore(templateContext.getCookieStore());
            String pageContent = getPageContent(oauth1.getLoginFormUrl(), httpClient, context);
            Assert.assertThat(pageContent, Matchers.containsString("<title>AUTH_RESPONSE</title>"));
            Assert.assertThat(context.getRedirectLocations(), Matchers.notNullValue());
            Assert.assertThat(context.getRedirectLocations(), Matchers.not(Matchers.empty()));
            String currentUrl = context.getRedirectLocations().get(0).toString();
            String code = getQueryFromUrl(currentUrl).get(OAuth2Constants.CODE);

            AtomicReference<OAuthClient.AccessTokenResponse> accessResRef = new AtomicReference<>();
            totalInvocations.incrementAndGet();

            // obtain access + refresh token via code-to-token flow
            OAuthClient.AccessTokenResponse accessRes = oauth1.doAccessTokenRequest(code, "password");
            Assert.assertEquals("AccessTokenResponse: client: " + oauth1.getClientId() + ", error: '" + accessRes.getError() + "' desc: '" + accessRes.getErrorDescription() + "'",
              200, accessRes.getStatusCode());
            accessResRef.set(accessRes);

            // Refresh access + refresh token using refresh token
            int invocationIndex = Retry.execute(() -> {
                OAuthClient.AccessTokenResponse refreshRes = oauth1.doRefreshTokenRequest(accessResRef.get().getRefreshToken(), "password");
                Assert.assertEquals("AccessTokenResponse: client: " + oauth1.getClientId() + ", error: '" + refreshRes.getError() + "' desc: '" + refreshRes.getErrorDescription() + "'",
                  200, refreshRes.getStatusCode());
            }, retryCount, retryDelayMs);

            retryHistogram[invocationIndex].incrementAndGet();

            if (userSessionId.get() == null) {
                AccessToken token = oauth1.verifyToken(accessResRef.get().getAccessToken());
                userSessionId.set(token.getSessionState());
            }
        }

        public int getRetryDelayMs() {
            return retryDelayMs;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public Map<Integer, Integer> getHistogram() {
            Map<Integer, Integer> res = new LinkedHashMap<>(retryCount);
            for (int i = 0; i < retryHistogram.length; i ++) {
                AtomicInteger item = retryHistogram[i];

                res.put(i * retryDelayMs, item.get());
            }
            return res;
        }
    }

    
}