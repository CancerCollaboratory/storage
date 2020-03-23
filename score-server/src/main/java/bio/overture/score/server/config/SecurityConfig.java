/*
 * Copyright (c) 2016 The Ontario Institute for Cancer Research. All rights reserved.
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
package bio.overture.score.server.config;

import bio.overture.score.server.metadata.MetadataService;
import bio.overture.score.server.security.CachingRemoteTokenServices;
import bio.overture.score.server.security.DownloadScopeAuthorizationStrategy;
import bio.overture.score.server.security.StudySecurity;
import bio.overture.score.server.security.UploadScopeAuthorizationStrategy;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.provider.authentication.BearerTokenExtractor;
import org.springframework.security.oauth2.provider.authentication.TokenExtractor;
import org.springframework.security.oauth2.provider.token.AccessTokenConverter;
import org.springframework.security.oauth2.provider.token.DefaultAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.RemoteTokenServices;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Resource service configuration file.<br>
 * Protects resources with access token obtained at the authorization server.
 */

@Slf4j
@Configuration
@Profile("secure")
@EnableWebSecurity
@EnableResourceServer
public class SecurityConfig extends ResourceServerConfigurerAdapter {

  private TokenExtractor tokenExtractor = new BearerTokenExtractor();

  @Value("${auth.server.studyPrefix}")
  private String studyPrefix;

  @Value("${auth.server.uploadSuffix}")
  private String uploadSuffix;

  @Value("${auth.server.downloadSuffix}")
  private String downloadSuffix;

  @Value("${auth.server.systemScope")
  private String systemScope;

  @Override
  public void configure(@NonNull HttpSecurity http) throws Exception {
    http.addFilterAfter(new OncePerRequestFilter() {

      @Override

      protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain)
        throws ServletException, IOException {

        // We don't want to allow access to a resource with no token so clear
        // the security context in case it is actually an OAuth2Authentication
        if (tokenExtractor.extract(request) == null) {
          SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
      }

    }, AbstractPreAuthenticatedProcessingFilter.class);

    http.csrf().disable();
    configureAuthorization(http);
  }

  @Bean
  public AccessTokenConverter accessTokenConverter() {
    return new DefaultAccessTokenConverter();
  }

  @Bean
  public RemoteTokenServices remoteTokenServices(
    final @Value("${auth.server.url}") String checkTokenUrl,
    final @Value("${auth.server.tokenName:token}") String tokenName,
    final @Value("${auth.server.clientId}") String clientId,
    final @Value("${auth.server.clientSecret}") String clientSecret) {
    val remoteTokenServices = new CachingRemoteTokenServices();
    remoteTokenServices.setCheckTokenEndpointUrl(checkTokenUrl);
    remoteTokenServices.setClientId(clientId);
    remoteTokenServices.setClientSecret(clientSecret);
    remoteTokenServices.setTokenName(tokenName);
    remoteTokenServices.setAccessTokenConverter(accessTokenConverter());

    log.debug("using auth server: " + checkTokenUrl);

    return remoteTokenServices;
  }

  private void configureAuthorization(HttpSecurity http) throws Exception {
    log.info("using system scope {}", systemScope);
    log.info("using study prefix: {}", studyPrefix);
    log.info("using upload suffix: {}", uploadSuffix);
    log.info("using download suffix: {}", downloadSuffix);

    // @formatter:off     
    http
      .authorizeRequests()
      .antMatchers("/health").permitAll()
      .antMatchers("/upload/**").permitAll()
      .antMatchers("/download/**").permitAll()
      .and()
      
      .authorizeRequests()
      .anyRequest().authenticated();
    // @formatter:on
    log.info("initialization done");
  }

  @Bean
  public UploadScopeAuthorizationStrategy projectSecurity(MetadataService song) {
    val security = studySecurity(uploadSuffix);
    return new UploadScopeAuthorizationStrategy(security, song);
  }

  @Bean
  @Scope("prototype")
  public DownloadScopeAuthorizationStrategy accessSecurity(MetadataService song) {
    val security = studySecurity(downloadSuffix);
    return new DownloadScopeAuthorizationStrategy(security, song);
  }

  private StudySecurity studySecurity(String suffix) {
    return StudySecurity.builder()
      .studyPrefix(studyPrefix)
      .studySuffix(suffix)
      .systemScope(systemScope)
      .build();
  }
}