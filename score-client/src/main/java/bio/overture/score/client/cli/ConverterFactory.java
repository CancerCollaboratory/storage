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
package bio.overture.score.client.cli;

import bio.overture.score.client.command.ViewCommand;
import bio.overture.score.client.download.OutputLayout;
import bio.overture.score.client.manifest.ManifestResource;
import bio.overture.score.client.mount.MountOptions;
import bio.overture.score.fs.StorageFileLayout;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.IStringConverterFactory;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.BaseConverter;

import java.util.Map;

public class ConverterFactory implements IStringConverterFactory {

  @Override
  public <T> Class<? extends IStringConverter<T>> getConverter(Class<T> type) {
    if (type.equals(ManifestResource.class)) return cast(ManifestResourceConverter.class);
    return null;
  }

  public static class OutputLayoutConverter extends EnumConverter<OutputLayout> {

    public OutputLayoutConverter(String optionName) {
      super(OutputLayout.class, optionName);
    }

  }

  public static class OutputTypeConverter extends EnumConverter<ViewCommand.OutputType> {

    public OutputTypeConverter(String optionName) {
      super(ViewCommand.OutputType.class, optionName);
    }

  }

  public static class OutputFormatConverter extends EnumConverter<ViewCommand.OutputFormat> {

    public OutputFormatConverter(String optionName) {
      super(ViewCommand.OutputFormat.class, optionName);
    }

  }

  public static class StorageFileLayoutConverter extends EnumConverter<StorageFileLayout> {

    public StorageFileLayoutConverter(String optionName) {
      super(StorageFileLayout.class, optionName);
    }

  }

  public static class ManifestResourceConverter extends BaseConverter<ManifestResource> {

    public ManifestResourceConverter(String optionName) {
      super(optionName);
    }

    @Override
    public ManifestResource convert(String value) {
      try {
        return new ManifestResource(value);
      } catch (Exception ex) {
        throw new ParameterException(getErrorString(value, "a manifest resource by id, url or file"));
      }
    }

  }

  public static class MountOptionsConverter extends BaseConverter<Map<String, String>> {

    public MountOptionsConverter(String optionName) {
      super(optionName);
    }

    @Override
    public Map<String, String> convert(String value) {
      try {
        return MountOptions.parseOptions(value);
      } catch (Exception ex) {
        throw new ParameterException(getErrorString(value, "mount options"));
      }
    }

  }

  @SuppressWarnings("unchecked")
  private <T> Class<? extends IStringConverter<T>> cast(Class<?> type) {
    return (Class<? extends IStringConverter<T>>) type;
  }

}
