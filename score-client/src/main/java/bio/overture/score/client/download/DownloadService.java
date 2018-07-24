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
package bio.overture.score.client.download;

import bio.overture.score.client.cli.Terminal;
import bio.overture.score.client.exception.NotResumableException;
import bio.overture.score.client.exception.NotRetryableException;
import bio.overture.score.client.exception.RetryableException;
import bio.overture.score.client.metadata.Entity;
import bio.overture.score.client.progress.Progress;
import bio.overture.score.client.transport.StorageService;
import bio.overture.score.client.transport.Transport;
import bio.overture.score.core.model.ObjectSpecification;
import bio.overture.score.core.model.Part;
import bio.overture.score.core.util.MD5s;
import com.google.common.io.BaseEncoding;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;

@Slf4j
@Component
public class DownloadService {

  /**
   * Configuration.
   */
  @Value("${client.quiet}")
  private boolean quiet;
  @Value("${storage.retryNumber}")
  private int retryNumber;

  /**
   * Dependencies.
   */
  @Autowired
  private StorageService storageService;
  @Autowired
  private DownloadStateStore downloadStateStore;
  @Autowired
  private Transport.Builder transportBuilder;
  @Autowired
  private Terminal terminal;

  @PostConstruct
  public void setup() {
    retryNumber = retryNumber < 0 ? Integer.MAX_VALUE : retryNumber;
  }

  @SneakyThrows
  public URL getUrl(@NonNull String objectId) {
    return getUrl(objectId, 0, -1);
  }

  /**
   * This method returns a pre-signed URL for downloading the blob associated with the objectId S3 key.
   * @param objectId
   * @param offset
   * @param length
   * @return
   */
  @SneakyThrows
  public URL getUrl(@NonNull String objectId, long offset, long length) {
    val spec = storageService.getExternalDownloadSpecification(objectId, offset, length);
    val file = getOnlyElement(spec.getParts()); // Throws IllegalArgumentException if more than one part

    return new URL(file.getUrl());
  }

  @SneakyThrows
  public Optional<String> getExpectedMd5(@NonNull String objectId) throws IOException {
    return Optional.ofNullable(storageService.getDownloadSpecification(objectId).getObjectMd5());
  }

  /**
   * The only public method for client to call to download data from remote storage.
   * 
   * @param downloadRequest
   * @param redo
   * @throws IOException
   */
  public void download(DownloadRequest downloadRequest, boolean redo) throws IOException {
    log.debug("Beginning download of {}", downloadRequest.toString());
    int retry = 0;
    for (; retry < retryNumber; retry++) {
      try {
        if (redo) {
          resetDownload(downloadRequest.getOutputFilePath());
          startNewDownload(downloadRequest);
        } else {
          // Only perform checksum the first time of the resume
          resumeIfPossible(downloadRequest, retry == 0 ? true : false);
        }
        return;
      } catch (NotResumableException e) {
        log.error("Failed to handle download request", e.getCause());
        throw e;
      } catch (NotRetryableException e) {
        log.warn(
            "Download failed during last execution. Checking data integrity. Please wait...", e);
        if (storageService.isDownloadDataRecoverable(downloadRequest.getOutputDir(), downloadRequest.getObjectId(),
            downloadRequest.getOutputFilePath().length())) {
          redo = false;
        } else {
          redo = true;
        }
      } catch (RetryableException e) {
        redo = true;
      }
    }
    if (retry == retryNumber) {
      throw new RuntimeException("Number of retries exhausted");
    }
  }

  protected void resetDownload(File objFile) {
    if (objFile.exists()) {
      // Delete if already there
      checkState(objFile.delete());
    }
  }

  public Optional<ObjectSpecification> getDownloadStateProgress(File outputDir, String objectId) throws IOException{
    try {
      return Optional.of(downloadStateStore.getProgress(outputDir, objectId));
    } catch (NotRetryableException e) {
      return Optional.empty();
    }
  }

  private void resumeIfPossible(DownloadRequest request, boolean checksum)
      throws IOException {
    log.debug("Attempting to resume download for {}", request.toString());
    ObjectSpecification spec = null;
    try {
      spec = downloadStateStore.getProgress(request.getOutputDir(), request.getObjectId());
    } catch (NotRetryableException e) {
      log.info("New download: {} because {}", request.getObjectId(), e.getMessage());
      terminal.printStatus("Restarting ");
      resetDownload(request.getOutputFilePath());
      startNewDownload(request);
      return;
    }
    resume(request, spec, checksum);
  }

  private void resume(DownloadRequest request, ObjectSpecification spec, boolean checksum) {
    log.info("Resuming from previous download...");

    val totalParts = spec.getParts().size();
    val completedParts = numCompletedParts(spec.getParts());
    val remainingParts = totalParts - completedParts;

    log.info("Total parts: {}, completed parts: {}, remaining parts: {}", totalParts, completedParts, remainingParts);
    val progress = new Progress(terminal, quiet, totalParts, completedParts);
    downloadParts(spec.getParts(), request.getOutputDir(), request.getObjectId(), request.getObjectId(), progress,
        checksum);

    if (request.isValidate()) {
      terminal.printStatus("Verifying checksum...");
      doMd5Checksum(request, spec);
      terminal.printStatus("Ok");
    }
  }

  /**
   * Calculate the number of completed parts
   */
  private int numCompletedParts(List<Part> parts) {

    int completedTotal = 0;
    for (val part : parts) {
      if (part.getMd5() != null) completedTotal++;
    }
    return completedTotal;

  }

  /**
   * Calculate the total size of completed parts
   */
  @SuppressWarnings("unused")
  private long completedPartsUsedSpace(List<Part> parts) {
    long completedSize = 0;

    for (val part : parts) {
      if (part.getMd5() != null) completedSize += part.getPartSize();
    }
    return completedSize;

  }

  /**
   * Computes space requirements for download and ensure there is sufficient space locally to store it
   */
  @SneakyThrows
  public long getSpaceRequired(Set<Entity> entities) {
    long total = 0L;

    for (val entity : entities) {
      val spec = storageService.getDownloadSpecification(entity.getId());
      total += spec.getObjectSize();
    }

    return total;
  }

  /**
   * Start a download given the object id
   */
  @SneakyThrows
  private void startNewDownload(DownloadRequest request) {
    log.info("Starting a new download...");
    val objFile = request.getOutputFilePath();

    if (objFile.exists()) {
      throw new NotResumableException(new FileAlreadyExistsException(objFile.getPath()));
    }

    val dir = request.getOutputDir();
    if (!dir.exists()) {
      log.debug("{} did not exist; creating now", dir.toString());
      Files.createDirectories(dir.toPath());
      log.debug("finished creating {}", dir.toString());
    }

    log.debug("Downloading specification for {}: {}-{}", request.getObjectId(), request.getOffset(),
        request.getLength());
    val spec = storageService.getDownloadSpecification(request.getObjectId(), request.getOffset(), request.getLength());
    log.info("Finished retrieving download specification file");

    // *****
    // TODO: verify whether source md5 values are present
    // doesn't really matter if we're just going to skip silently if part md5's aren't there
    // *****

    downloadStateStore.init(dir, spec);

    // TODO: Assign session id
    val progress = new Progress(terminal, quiet, spec.getParts().size(), 0);
    downloadParts(spec.getParts(), dir, request.getObjectId(), request.getObjectId(), progress, false);

    if (request.isValidate()) {
      terminal.printStatus("Verifying checksum...");
      log.info("Beginning MD5 checksum calculation for {}", request.getOutputFilePath().toString());
      doMd5Checksum(request, spec);
    }

  }

  /**
   * start downloading parts using a specific configured data transport
   */
  @SneakyThrows
  private void downloadParts(List<Part> parts, File file, String objectId, String sessionId, Progress progressBar,
      boolean checksum) {
    log.debug("Setting up download of parts");
    transportBuilder.withProxy(storageService)
        .withProgressBar(progressBar)
        .withParts(parts)
        .withObjectId(objectId)
        .withTransportMode(Transport.Mode.DOWNLOAD)
        .withChecksum(checksum)
        .withSessionId(sessionId);
    transportBuilder.build().receive(file);
  }

  private void doMd5Checksum(DownloadRequest req, ObjectSpecification spec) {

    if (spec.getObjectMd5() == null) {
      log.warn("meta file does not contain the object MD5 checksum. Skipping check.");
      return;
    }
    val outputFile = req.getOutputFilePath();
    val downloadedMd5 = calculateChecksum(outputFile);

    boolean check;
    try {
      check = MD5s.isEqual(downloadedMd5, spec.getObjectMd5());
    } catch (IllegalArgumentException e) {
      log.error("MD5's not recognized as either HEX or BASE64: of downloaded file: {}, spec file: {}", downloadedMd5,
          spec.getObjectMd5());
      throw new NotRetryableException(e);
    }

    if (check) {
      log.info("MD5 for {} validated correctly", outputFile.getAbsolutePath());
    } else {
      val msg = String.format("MD5 for %s was %s but was expecting %s", outputFile.getAbsolutePath(), downloadedMd5,
          spec.getObjectMd5());
      log.error(msg);
      terminal.printWarn(msg);
      throw new RetryableException(msg);
    }
  }

  private String calculateChecksum(File outputFile) {
    String downloadedMd5 = null;
    try {
      val md = MessageDigest.getInstance("MD5");
      val fis = new FileInputStream(outputFile);
      val fchannel = fis.getChannel();
      val buffer = ByteBuffer.allocateDirect(8192); // allocation in bytes - 1024, 2048, 4096, 8192

      int byteCount = fchannel.read(buffer);
      while ((byteCount != -1) && (byteCount != 0)) {
        buffer.flip();
        byte[] bytes = new byte[byteCount];
        buffer.get(bytes);
        md.update(bytes, 0, byteCount);
        buffer.clear();
        byteCount = fchannel.read(buffer);
      }

      fis.close();
      downloadedMd5 = decodeDigest(md.digest());

    } catch (NoSuchAlgorithmException e) {
      throw new NotRetryableException(e);
    } catch (IOException ioe) {
      throw new NotRetryableException(ioe);
    }
    return downloadedMd5;
  }

  private String decodeDigest(byte[] digest) {
    return BaseEncoding.base16().lowerCase().encode(digest);
  }

}
