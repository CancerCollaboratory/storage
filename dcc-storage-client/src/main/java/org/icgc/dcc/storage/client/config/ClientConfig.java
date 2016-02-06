/*
 * Copyright (c) 2015 The Ontario Institute for Cancer Research. All rights reserved.                             
 *                                                                                                               
 * This program and the accompanying materials are made available under the terms of the GNU Public License v3.0.
 * You should have received a copy of the GNU General Public License along with                                  
 * this program. If not, see <http://www.gnu.org/licenses/>.                                                     
 *                                                                                                               
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY                           
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES                          
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT                           
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,                                
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED                          
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;                               
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER                              
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN                         
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.icgc.dcc.storage.client.config;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static javax.ws.rs.core.HttpHeaders.AUTHORIZATION;

import java.io.IOException;

import javax.net.ssl.SSLContext;

import lombok.SneakyThrows;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.icgc.dcc.storage.client.download.DownloadStateStore;
import org.icgc.dcc.storage.client.exception.AmazonS3RetryableResponseErrorHandler;
import org.icgc.dcc.storage.client.exception.NotResumableException;
import org.icgc.dcc.storage.client.exception.NotRetryableException;
import org.icgc.dcc.storage.client.exception.RetryableException;
import org.icgc.dcc.storage.client.exception.ServiceRetryableResponseErrorHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

import com.google.common.collect.ImmutableMap;

/**
 * Configurations for connections for uploads
 */
@Slf4j
@Configuration
@EnableConfigurationProperties
@Import(PropertyPlaceholderAutoConfiguration.class)
public class ClientConfig {

  /**
   * Configuration.
   */
  @Autowired
  private ClientProperties properties;

  /**
   * Dependencies.
   */
  @Autowired
  private SSLContext sslContext;
  @Autowired
  private X509HostnameVerifier hostnameVerifier;

  @Bean
  public DownloadStateStore downloadStateStore() {
    return new DownloadStateStore();
  }

  @Bean
  public RestTemplate serviceTemplate() {
    val serviceTemplate = new RestTemplate(clientHttpRequestFactory());
    serviceTemplate.setErrorHandler(new ServiceRetryableResponseErrorHandler());

    return serviceTemplate;
  }

  @Bean
  public RestTemplate dataTemplate() {
    val dataTemplate = new RestTemplate(streamingClientHttpRequestFactory());
    dataTemplate.setErrorHandler(new AmazonS3RetryableResponseErrorHandler());

    return dataTemplate;
  }

  private HttpComponentsClientHttpRequestFactory clientHttpRequestFactory() {
    val factory = new HttpComponentsClientHttpRequestFactory();

    factory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);
    factory.setConnectTimeout(properties.getConnectTimeoutSeconds() * 1000);
    factory.setHttpClient(sslClient());

    return factory;
  }

  @SneakyThrows
  private HttpClient sslClient() {
    val client = HttpClients.custom();
    client.setSslcontext(sslContext);
    client.setHostnameVerifier(hostnameVerifier);
    configureOAuth(client);

    return client.build();
  }

  private SimpleClientHttpRequestFactory streamingClientHttpRequestFactory() {
    val factory = new SimpleClientHttpRequestFactory();

    // See http://stackoverflow.com/questions/9934970/can-i-globally-set-the-timeout-of-http-connections#answer-10705424
    System.setProperty("sun.net.client.defaultConnectTimeout",
        Long.toString(properties.getConnectTimeoutSeconds() * 1000));
    System.setProperty("sun.net.client.defaultReadTimeout", Long.toString(properties.getReadTimeoutSeconds() * 1000));
    factory.setOutputStreaming(true);
    factory.setBufferRequestBody(false);
    return factory;
  }

  @Bean
  public RetryTemplate retryTemplate(
      @Value("${storage.retryNumber}") int retryNumber,
      @Value("${storage.retryTimeout}") int retryTimeout) {
    val maxAttempts = retryNumber < 0 ? Integer.MAX_VALUE : retryNumber;

    val exceptions = ImmutableMap.<Class<? extends Throwable>, Boolean> builder();
    exceptions.put(Error.class, Boolean.FALSE);
    exceptions.put(NotResumableException.class, Boolean.FALSE);
    exceptions.put(NotRetryableException.class, Boolean.FALSE);
    exceptions.put(RetryableException.class, Boolean.TRUE);
    exceptions.put(IOException.class, Boolean.TRUE);

    val retryPolicy = new SimpleRetryPolicy(maxAttempts, exceptions.build(), true);
    val backOffPolicy = new ExponentialBackOffPolicy();

    val retry = new RetryTemplate();
    retry.setBackOffPolicy(backOffPolicy);
    retry.setRetryPolicy(retryPolicy);
    return retry;

  }

  private void configureOAuth(HttpClientBuilder client) {
    val accessToken = properties.getAccessToken();

    val defined = accessToken != null;
    if (defined) {
      log.debug("Setting access token: {}", accessToken);
      client.setDefaultHeaders(singletonList(new BasicHeader(AUTHORIZATION, format("Bearer %s", accessToken))));
    }
  }

}
