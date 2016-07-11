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
package org.icgc.dcc.storage.server.controller;

import javax.servlet.http.HttpServletRequest;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.icgc.dcc.storage.core.model.ObjectSpecification;
import org.icgc.dcc.storage.server.service.download.ObjectDownloadService;
import org.icgc.dcc.storage.server.util.TokenHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.net.HttpHeaders;

/**
 * A controller to expose RESTful API for download
 */
@Setter
@RestController
@RequestMapping("/download")
@Slf4j
@Profile({ "prod", "default", "debug" })
public class ObjectDownloadController {

  @Autowired
  ObjectDownloadService downloadService;

  @RequestMapping(method = RequestMethod.GET, value = "/ping")
  public @ResponseBody String ping(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = true) final String accessToken,
      @RequestHeader(value = "User-Agent", defaultValue = "unknown") String userAgent,
      HttpServletRequest request) {

    String ipAddress = request.getHeader(HttpHeaders.X_FORWARDED_FOR);
    if (ipAddress == null) {
      ipAddress = request.getRemoteAddr();
    }

    log.info("Requesting download of sentinel object id with access token {} (MD5) from {} and client version {}",
        TokenHasher.hashToken(accessToken), ipAddress, userAgent);
    return downloadService.getSentinelObject();
  }

  @RequestMapping(method = RequestMethod.GET, value = "/{object-id}")
  public @ResponseBody ObjectSpecification downloadPartialObject(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = true) final String accessToken,
      @PathVariable(value = "object-id") String objectId,
      @RequestParam(value = "offset", required = true) long offset,
      @RequestParam(value = "length", required = true) long length,
      @RequestParam(value = "external", defaultValue = "false") boolean external,
      @RequestHeader(value = "User-Agent", defaultValue = "unknown") String userAgent,
      HttpServletRequest request) {

    String ipAddress = request.getHeader(HttpHeaders.X_FORWARDED_FOR);
    if (ipAddress == null) {
      ipAddress = request.getRemoteAddr();
    }

    log.info("Requesting download of object id {} with access token {} (MD5) from {} and client version {}", objectId,
        TokenHasher.hashToken(accessToken), ipAddress, userAgent);
    return downloadService.download(objectId, offset, length, external);
  }
}
