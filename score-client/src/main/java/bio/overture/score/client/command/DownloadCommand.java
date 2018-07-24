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
package bio.overture.score.client.command;

import bio.overture.score.client.cli.ConverterFactory.OutputLayoutConverter;
import bio.overture.score.client.cli.CreatableDirectoryValidator;
import bio.overture.score.client.cli.ObjectIdListValidator;
import bio.overture.score.client.download.DownloadRequest;
import bio.overture.score.client.download.DownloadService;
import bio.overture.score.client.download.OutputLayout;
import bio.overture.score.client.manifest.ManifestResource;
import bio.overture.score.client.manifest.ManifestService;
import bio.overture.score.client.metadata.Entity;
import bio.overture.score.client.metadata.MetadataService;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Multimaps;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static bio.overture.score.client.cli.Parameters.checkParameter;
import static com.google.common.io.Files.hash;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.icgc.dcc.common.core.util.Formats.formatBytes;
import static org.icgc.dcc.common.core.util.stream.Collectors.toImmutableSet;

@Slf4j
@Component
@Parameters(separators = "=", commandDescription = "Retrieve file object(s) from the remote storage repository")
public class DownloadCommand extends RepositoryAccessCommand {

  /**
   * Options
   */
  @Parameter(names = "--output-dir", description = "Path to output directory", required = true, validateValueWith = CreatableDirectoryValidator.class)
  private File outputDir;
  @Parameter(names = "--output-layout", description = "Layout of the output-dir. One of 'bundle' (saved according to filename under GNOS bundle id directory), 'filename' (saved according to filename in output directory), or 'id' (saved according to object id in output directory)", converter = OutputLayoutConverter.class)
  private OutputLayout layout = OutputLayout.FILENAME;
  @Parameter(names = "--force", description = "Force re-download (override local file)")
  private boolean force = false;
  @Parameter(names = "--manifest", description = "Manifest id, url, or path to manifest file")
  private ManifestResource manifestResource;
  @Parameter(names = "--object-id", description = "Object id to download", validateValueWith = ObjectIdListValidator.class, variableArity = true)
  private List<String> objectId = new ArrayList<>();
  @Parameter(names = "--offset", description = "The byte position in source file to begin download from")
  private long offset = 0;
  @Parameter(names = "--length", description = "The number of bytes to download")
  private long length = -1;
  @Parameter(names = "--index", description = "Download file index if available?", arity = 1)
  private boolean index = true;
  @Parameter(names = "--validate", description = "Perform check of MD5 checksum (if available)", arity = 1)
  private boolean validate = true;
  @Parameter(names = "--verify-connection", description = "Verify connection to repository", arity = 1)
  private boolean verifyConnection = true;
  @Parameter(names = "--skip-existing-md5-check", description = "If the file exists, skip the md5 checksum comparison")
  private boolean skipExistingMd5Check = false;

  /**
   * Dependencies
   */
  @Autowired
  private ManifestService manifestService;
  @Autowired
  private MetadataService metadataService;
  @Autowired
  private DownloadService downloadService;

  @Override
  public int execute() throws Exception {
    validateParms();
    if (verifyConnection) {
      verifyRepoConnection();
    }
    validateOutputDirectory();

    terminal.printStatus("Downloading...");

    val listed = objectId.size() > 0;
    if (listed) {
      // Ad-hoc list of object id's supplied from command line
      return downloadObjects(objectId);
    } else {
      // Manifest based
      if (manifestResource.isGnosManifest()) {
        terminal
            .printError(
                "Manifest '%s' looks like a GNOS-format manifest file. Please ensure you are using a tab-delimited text file"
                    + " manifest from https://dcc.icgc.org/repositories",
                manifestResource.getValue());
        return FAILURE_STATUS;
      }
      val manifest = manifestService.getDownloadManifest(manifestResource);

      validateManifest(manifest);

      val entries = manifest.getEntries();
      if (entries.isEmpty()) {
        terminal.printError("Manifest '%s' is empty", manifestResource);
        return FAILURE_STATUS;
      }

      return downloadObjects(manifest.getEntries().stream().map(entry -> entry.getFileUuid()).collect(toList()));
    }
  }

  private int downloadObjects(List<String> objectIds) throws IOException {
    // Entities are defined in Meta service
    val entities = resolveEntities(objectIds);

    if (!verifyLocalAvailableSpace(entities)) {
      return FAILURE_STATUS;
    }

    int i = 1;
    terminal.println("");

    // Filtering based on file name only applies to FILENAME and BUNDLE layouts
    Set<Entity> entitySet = entities;
    if (layout != OutputLayout.ID) {
      entitySet = filterEntities(entities);
    }

    for (val entity : entitySet) {
      val doDownload = force || !alreadyDownloaded(entity, skipExistingMd5Check);
      if (doDownload){
        terminal
            .printLine()
            .printf("[%s/%s] Downloading object: %s (%s)%n", i++, entitySet.size(), terminal.value(entity.getId()),
                entity.getFileName())
            .printLine();

        val request = DownloadRequest.builder()
            .outputDir(outputDir)
            .entity(entity)
            .objectId(entity.getId())
            .offset(offset)
            .length(length)
            .validate(validate)
            .build();

        downloadService.download(request, force);
        finalizeDownload(entity);
      } else {
        terminal
            .printLine()
            .printf("[%s/%s] Skipping Download for Object: %s (%s)%n", i++, entitySet.size(), terminal.value(entity.getId()),
                entity.getFileName())
            .printLine();
      }

      terminal.println("Done.");
    }

    return SUCCESS_STATUS;
  }

  @SneakyThrows
  private boolean isResumeable(Entity entity){
    return downloadService.getDownloadStateProgress(outputDir, entity.getId()).isPresent();
  }

  @SneakyThrows
  private boolean alreadyDownloaded(Entity entity, boolean skipExistingMd5Check)  {
    val target = getLayoutTarget(entity);

    // Check if final path exists
    if (target.exists()){

      // Continue with download if it was previously started
      if (isResumeable(entity)){
        return false;
      }

      // Since file exists, check its actual md5 against the expected
      if (!skipExistingMd5Check){
        val expectedMd5Result = downloadService.getExpectedMd5(entity.getId());

        // Check there is an expected md5, if there isnt then assume the file has been downloaded already
        if (expectedMd5Result.isPresent()){
          val expectedMd5 = expectedMd5Result.get();
          val actualMd5 = calculateMd5(target);

          // Skip downloading since md5s match
          if (expectedMd5.equals(actualMd5)){
            val message = format("File already downloaded with matching checksum comparison, "
                    + "skipping download: objectId=%s",
                entity.getId());
            log.warn(message);
            terminal.printWarn(message);

           // Do the download since the md5s dont match
          } else {
            val message = format("File exists but has a mismatching checksum. "
                    + "Re-downloading : objectId=%s   expectedMd5=%s   actualMd5=%s",
                entity.getId(), expectedMd5, actualMd5);
            log.warn(message);
            terminal.printWarn(message);
            return false;
          }

          // Skip downloading since md5 doesnt exist
        } else {
          val message = format("File exists but does not have an expected MD5, skipping download: objectId=%s",
              entity.getId());
          log.warn(message);
          terminal.printWarn(message);
        }

        // Skip the download since file exists and forcibly skipping md5 checking
      } else {
        val message = format("File already downloaded, skipping download and checksum comparison: objectId=%s",
            entity.getId());
        log.warn(message);
        terminal.printWarn(message);
      }
      return true;
    }
    return false;
  }

  @SneakyThrows
  private static String calculateMd5(File file){
    return hash(file, Hashing.md5()).toString();
  }

  /**
   * Move the entity into its final destination. File is initially downloaded into file named with object id. To
   * complete download, it is renamed to filename stored in Metadata record
   */
  private void finalizeDownload(Entity entity) throws IOException {
    val source = getLayoutSource(entity);
    val target = getLayoutTarget(entity);
    if (target.equals(source)) {
      return;
    }

    // For cases like layout = bundle, make sure sub-directory exists
    val targetDir = target.getParentFile();
    checkParameter(targetDir.exists() || targetDir.mkdirs(), "Could not create target sub-directory '%s'", targetDir);

    checkParameter(!target.exists() || force && target.delete(),
        "Download file '%s' already exists and --force was not specified", target);

    Files.move(source, target);
  }

  /**
   * Get the source file for the supplied {@code entity}.
   */
  private File getLayoutSource(Entity entity) {
    return new File(outputDir, entity.getId());
  }

  /**
   * Get the destination file for the supplied {@code entity}.
   */
  private File getLayoutTarget(Entity entity) {
    if (layout == OutputLayout.BUNDLE) {
      // "bundle/filename"
      val bundleDir = new File(outputDir, entity.getGnosId());
      val target = new File(bundleDir, entity.getFileName());

      return target;
    } else if (layout == OutputLayout.FILENAME) {
      // "filename"
      val target = new File(outputDir, entity.getFileName());

      return target;
    } else if (layout == OutputLayout.ID) {
      // "id"
      val target = new File(outputDir, entity.getId());

      return target;
    }

    throw new IllegalStateException("Unsupported layout: " + layout);
  }

  /**
   * Lookup entities by {@code objectId} from the metadata service.
   */
  private Set<Entity> resolveEntities(List<String> objectIds) {
    // Set to remove duplicates
    // We don't want it to be immutable (there's going to be subsequent filtering)
    val entities = new HashSet<Entity>();
    for (val objectId : objectIds) {
      val entity = metadataService.getEntity(objectId);
      entities.add(entity);

      if (index) {
        val indexEntity = metadataService.getIndexEntity(entity);
        if (indexEntity.isPresent()) {
          entities.add(indexEntity.get());
        }
      }
    }

    return entities;
  }

  /**
   * Filters downloadable entities. Strips out duplicate file names. This removes duplicates at source rather than
   * trying to resolve filenames when trying to finalize download.
   */
  private Set<Entity> filterEntities(Set<Entity> entities) {

    // ImmutableListMultimap preserves insert order
    val groupEntities = Multimaps.index(entities, Entity::getFileName);

    val normalizedEntities = new HashSet<Entity>();
    for (val fileName : groupEntities.keySet()) {
      // We only process first entity encountered for each file name
      // Contract for Multimap states if we have a key (file name), we have a collection with at least one member
      val entityCollection = groupEntities.get(fileName);
      normalizedEntities.add(entityCollection.get(0));

      if (entityCollection.size() > 1) {
        val firstObjectId = entityCollection.get(0).getId();
        // Inform user about every additional entity with the same file name
        for (int i = 1; i < entityCollection.size(); i++) {
          val e = entityCollection.get(i);
          terminal
              .printWarn(
                  "File '%s' (with object id '%s') already scheduled for download. Omitting file with duplicate name (and object id '%s')",
                  e.getFileName(), firstObjectId, e.getId());
        }
      }
    }

    return normalizedEntities;
  }

  @SneakyThrows
  private void validateOutputDirectory() {
    checkParameter(outputDir.exists() || outputDir.mkdirs(), "Could not create output directory %s", outputDir);
  }

  @SneakyThrows
  private long getLocalAvailableSpace() {
    val path = Paths.get(outputDir.getAbsolutePath());
    val fs = java.nio.file.Files.getFileStore(path);
    return fs.getUsableSpace();
  }

  private boolean fileExists(Entity entity){
    val target = getLayoutTarget(entity);
    val source = getLayoutSource(entity);
    return target.exists() || source.exists();
  }

  private boolean verifyLocalAvailableSpace(Set<Entity> entities) {
    // Filter out files that already exist
    val nonExistingEntities = entities.stream()
        .filter(x -> !fileExists(x))
        .collect(toImmutableSet());

    val spaceRequired = downloadService.getSpaceRequired(nonExistingEntities);
    val spaceAvailable = getLocalAvailableSpace();
    log.warn("Space required: {} ({})  Space available: {} ({})",
        formatBytes(spaceRequired), spaceRequired, formatBytes(spaceAvailable), spaceAvailable);

    if (spaceRequired > spaceAvailable) {
      terminal.printWarn("Insufficient space to download requested files: Require %s. %s Available",
          formatBytes(spaceRequired), formatBytes(spaceAvailable));
      terminal.clearLine();
      return false;
    }

    return true;
  }

  private void validateParms() {
    checkParameter(objectId != null || manifestResource != null, "One of --object-id or --manifest must be specified");
  }
}
