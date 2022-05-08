package dk.jkiddo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.util.BundleBuilder;
import com.google.common.base.Strings;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;

public class EmberApplication implements ApplicationRunner {

  public static final String PACKAGE_EXAMPLE = "package/example";

  private static final Logger LOG = LoggerFactory
      .getLogger(EmberApplication.class);
  private FhirContext fhirContext;
  private IPackageCacheManager packageManager;

  @Value("${serverBase:}")
  String serverBase;
  @Value("${igIdAndVersion:}")
  String igIdAndVersion;
  @Value("${loadRecursively:false}")
  boolean loadRecursively;
  @Value("${includeSearchBundles:false}")
  boolean includeSearchBundles;

  public static void main(String[] args) {
    LOG.info("STARTING THE APPLICATION");
    SpringApplication.run(EmberApplication.class, args);
    LOG.info("APPLICATION FINISHED");
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {

    if (Strings.isNullOrEmpty(igIdAndVersion)) {
      LOG.error("No igIdAndVersion supplied ... - exiting");
      System.exit(-1);
    }

    packageManager = new FilesystemPackageCacheManager(true, new Random().nextInt());
    NpmPackage npmPackage = loadNpmPackage();

    fhirContext = toContext.apply(npmPackage.fhirVersion());

    var clientFactory = fhirContext.getRestfulClientFactory();
    clientFactory.setServerValidationMode(ServerValidationModeEnum.NEVER);

    var bundleBuilder = new BundleBuilder(fhirContext);
    loadResources(npmPackage).forEach(bundleBuilder::addTransactionCreateEntry);
    var bundle = bundleBuilder.getBundle();

    if (Strings.isNullOrEmpty(serverBase)) {
      LOG.info("Sending transaction bundle to console");
      LOG.info(fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));
    } else {
      LOG.info("Sending transaction bundle to: " + serverBase);
      var response = clientFactory.newGenericClient(
          serverBase).transaction().withBundle(bundle).execute();
      LOG.info("Response from server was: " + fhirContext.newJsonParser().setPrettyPrint(true)
          .encodeResourceToString(response));
    }
  }

  @NotNull
  private Collection<IBaseResource> loadResources(NpmPackage npmPackage) {
    Collection<IBaseResource> resources;
    if (loadRecursively) {
      resources = loadExampleResourcesRecursively(npmPackage);
    } else {
      resources = loadExampleResources(npmPackage);
    }

    if (!includeSearchBundles) {
      resources = resources.stream().filter(r -> !isSearchBundle.test(r))
          .collect(Collectors.toList());
    }
    return resources;
  }

  private NpmPackage loadNpmPackage() throws IOException {
    NpmPackage npmPackage = null;

    try {
      npmPackage = packageManager.loadPackage(igIdAndVersion);
    } catch (NullPointerException npe) {
      LOG.error("Could not load package: " + igIdAndVersion);
      LOG.info(npe.getMessage(), npe);
      System.exit(-2);
    }
    return npmPackage;
  }

  private @NotNull Collection<IBaseResource> loadExampleResourcesRecursively(
      NpmPackage npmPackage) {

    var dependencyExampleResources = npmPackage.dependencies().stream().map(toNpmPackage)
        .map(this::loadExampleResourcesRecursively)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    return Stream.of(dependencyExampleResources,
            loadExampleResources(npmPackage))
        .flatMap(Collection::stream).collect(Collectors.toList());
  }

  @NotNull
  private List<IBaseResource> loadExampleResources(NpmPackage npmPackage) {
    LOG.info("Loading sample resources from " + npmPackage.name());
    var exampleFolder = npmPackage.getFolders().get(PACKAGE_EXAMPLE);

    if (exampleFolder == null) {
      LOG.info("Found no example resources in " + npmPackage.name());
      return Collections.EMPTY_LIST;
    }

    var fileNames = exampleFolder.getTypes().values().stream()
        .flatMap(Collection::stream).collect(
            Collectors.toList());
    LOG.info("Found " + fileNames.size() + " example resources in " + npmPackage.name());

    return fileNames.stream().map(fileName -> {
          try {
            return new String(exampleFolder.fetchFile(fileName));
          } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
          }
        })
        .map(fhirContext.newJsonParser().setSuppressNarratives(true)::parseResource)
        .collect(
            Collectors.toList());
  }

  Function<String, FhirContext> toContext = (fhirVersionAsString) -> new FhirContext(
      FhirVersionEnum.forVersionString(fhirVersionAsString));

  Function<String, NpmPackage> toNpmPackage = (nameAndVersion) -> {
    try {
      return packageManager.loadPackage(nameAndVersion);
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  };

  Predicate<IBaseResource> isSearchBundle = (resource) ->
  {
    if (org.hl7.fhir.dstu2.model.Bundle.class.isInstance(resource)) {
      return ((org.hl7.fhir.dstu2.model.Bundle) resource).getTypeElement().getValue()
          == org.hl7.fhir.dstu2.model.Bundle.BundleType.SEARCHSET;
    }
    if (org.hl7.fhir.dstu3.model.Bundle.class.isInstance(resource)) {
      return ((org.hl7.fhir.dstu3.model.Bundle) resource).getTypeElement().getValue()
          == org.hl7.fhir.dstu3.model.Bundle.BundleType.SEARCHSET;
    }
    if (org.hl7.fhir.r4.model.Bundle.class.isInstance(resource)) {
      return ((org.hl7.fhir.r4.model.Bundle) resource).getTypeElement().getValue()
          == org.hl7.fhir.r4.model.Bundle.BundleType.SEARCHSET;
    }
    if (org.hl7.fhir.r5.model.Bundle.class.isInstance(resource)) {
      return ((org.hl7.fhir.r5.model.Bundle) resource).getTypeElement().getValue()
          == org.hl7.fhir.r5.model.Bundle.BundleType.SEARCHSET;
    }
    return false;
  };

  /**
   * Copied and modified from ca.uhn.fhir.jpa.packages.JpaPackageCache in order to have some consistency
   * @param thePackageUrl
   * @return
   */
  protected byte[] loadPackageUrlContents(String thePackageUrl) {
    if (thePackageUrl.startsWith("file:")) {
      try {
        byte[] bytes = Files.readAllBytes(Paths.get(new URI(thePackageUrl)));
        return bytes;
      } catch (IOException | URISyntaxException e) {
        throw new InternalErrorException(
            Msg.code(2024) + "Error loading \"" + thePackageUrl + "\": " + e.getMessage());
      }
    } else {
      HttpClientConnectionManager connManager = new BasicHttpClientConnectionManager();
      try (CloseableHttpResponse request = HttpClientBuilder
          .create()
          .setConnectionManager(connManager)
          .build()
          .execute(new HttpGet(thePackageUrl))) {
        if (request.getStatusLine().getStatusCode() != 200) {
          throw new ResourceNotFoundException(Msg.code(1303) + "Received HTTP " + request.getStatusLine().getStatusCode() + " from URL: " + thePackageUrl);
        }
        return IOUtils.toByteArray(request.getEntity().getContent());
      } catch (IOException e) {
        throw new InternalErrorException(Msg.code(1304) + "Error loading \"" + thePackageUrl + "\": " + e.getMessage());
      }
    }
  }
}
