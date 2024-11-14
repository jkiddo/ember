package dk.jkiddo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.packages.loader.PackageLoaderSvc;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.util.BundleBuilder;
import com.google.common.base.Strings;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, SwaggerConfig.class, ElasticsearchRestClientAutoConfiguration.class})
public class EmberApplication implements ApplicationRunner {

    public static final String PACKAGE_EXAMPLE = "package/example";
    public static final String PACKAGE = "package";

    private static final Logger LOG = LoggerFactory.getLogger(EmberApplication.class);
    private FhirContext fhirContext;
    private IPackageCacheManager packageManager;

    @Value("${serverBase:}")
    String serverBase;
    @Value("${packageId:}")
    String packageId;
    @Value("${location:}")
    String location;
    @Value("${await:60}")
    String seconds = "60";
    @Value("${loadRecursively:false}")
    boolean loadRecursively;
    @Value("${includeSearchBundles:false}")
    boolean includeSearchBundles;
    @Value("${directory:}")
    String directory;
    @Value("${docsAndLists:false}")
    boolean docsAndLists;
    @Value("${includeRoot:false}")
    boolean includeRoot;
    @Value("${usePUT:false}")
    boolean usePUT;


    public static void main(String[] args) {
        SpringApplication.run(EmberApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {

        IBaseBundle resultingExampleBundle;

        if (!Strings.isNullOrEmpty(directory))
            resultingExampleBundle = loadResourcesFromDirectory(new File(directory));
        else
            resultingExampleBundle = loadResourcesFromNPM();

        emitBundle(resultingExampleBundle);
        System.exit(0);
    }

    @NotNull
    private IBaseBundle loadResourcesFromDirectory(File directory) {

        var fhirContext = FhirContext.forR5();

        List<IBaseResource> resources = Stream.of(Objects.requireNonNull(directory.listFiles())).filter(file -> !file.isDirectory()).filter(file -> file.getName().endsWith(".json")).filter(file -> file.getName().contains("mplementation")).map(f -> {
            try {
                return new String(Files.readAllBytes(Path.of(f.getPath())));
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).map(fhirContext.newJsonParser().setSuppressNarratives(true)::parseResource).toList();

        var bundleBuilder = new BundleBuilder(fhirContext);
        if(usePUT) {
            resources.forEach(bundleBuilder::addTransactionUpdateEntry);
        } else {
            resources.forEach(bundleBuilder::addTransactionCreateEntry);
        }

        return bundleBuilder.getBundle();

    }

    @NotNull
    private IBaseBundle loadResourcesFromNPM() throws IOException {
        if (Strings.isNullOrEmpty(packageId) && Strings.isNullOrEmpty(location)) {
            LOG.error("No packageId or location supplied ... - exiting");
            System.exit(-1);
        }

        packageManager = new FilesystemPackageCacheManager.Builder().build();

        if (!Strings.isNullOrEmpty(location)) {
            installPackage();
        }

        final NpmPackage npmPackage = packageManager.loadPackage(packageId);
        fhirContext = new FhirContext(FhirVersionEnum.forVersionString(npmPackage.fhirVersion()));

        return loadResources(npmPackage);
    }

    private Boolean serverReady(IRestfulClientFactory clientFactory) {

        try {
            clientFactory.newGenericClient(serverBase).search().forResource("StructureDefinition").execute();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void emitBundle(IBaseBundle bundle) {

        var clientFactory = fhirContext.getRestfulClientFactory();
        clientFactory.setServerValidationMode(ServerValidationModeEnum.NEVER);


        if (!Strings.isNullOrEmpty(serverBase)) {
            if (!Strings.isNullOrEmpty(seconds)) {
                await().atMost(Integer.parseInt(seconds), TimeUnit.MINUTES).until(() -> serverReady(clientFactory));
            }
        }

        if (Strings.isNullOrEmpty(serverBase)) {
            LOG.debug("Sending transaction bundle to console");
            LOG.info(fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));
        } else {
            LOG.debug("Sending transaction bundle to: " + serverBase);
            var response = clientFactory.newGenericClient(serverBase).transaction().withBundle(bundle).execute();
            LOG.debug("Response from server was: " + fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(response));
        }
    }

    private void installPackage() throws IOException {

        var npmAsBytes = new PackageLoaderSvc().loadPackageUrlContents(location);
        var npmPackage = NpmPackage.fromPackage(new ByteArrayInputStream(npmAsBytes));
        packageManager.addPackageToCache(npmPackage.id(), npmPackage.version(), new ByteArrayInputStream(npmAsBytes), npmPackage.description());
        LOG.warn("Overwriting parameterized packageId with packageId from location parameter. packageId was '" + packageId + "'. New packageId will be '" + npmPackage.id() + "#" + npmPackage.version() + "'");
        packageId = npmPackage.id() + "#" + npmPackage.version();
    }


    @NotNull
    private IBaseBundle loadResources(NpmPackage npmPackage) {
        Collection<IBaseResource> resources;
        if (loadRecursively) {
            resources = loadExampleResourcesRecursively(npmPackage);
        } else {
            resources = loadExampleResources(npmPackage);
        }

        if (!includeSearchBundles) {
            resources = resources.stream().filter(r -> !isSearchBundle.test(r)).collect(Collectors.toList());
        }

        if(docsAndLists) {
            resources = resources.stream().filter(r -> {
                if (r instanceof org.hl7.fhir.r5.model.Bundle) {
                    return ((Bundle) r).getTypeElement().getValue() == org.hl7.fhir.r5.model.Bundle.BundleType.DOCUMENT;
                }
                if (r instanceof org.hl7.fhir.r5.model.ListResource) {
                    return true;
                }
                return false;
            }).collect(Collectors.toList());
        }

        var bundleBuilder = new BundleBuilder(fhirContext);
        if(usePUT) {
            resources.forEach(bundleBuilder::addTransactionUpdateEntry);
        } else {
            resources.forEach(bundleBuilder::addTransactionCreateEntry);
        }
        return bundleBuilder.getBundle();
    }

    private @NotNull Collection<IBaseResource> loadExampleResourcesRecursively(NpmPackage npmPackage) {

        var dependencyExampleResources = npmPackage.dependencies().stream().map(toNpmPackage).map(this::loadExampleResourcesRecursively).flatMap(Collection::stream).collect(Collectors.toList());

        return Stream.of(dependencyExampleResources, loadExampleResources(npmPackage)).flatMap(Collection::stream).collect(Collectors.toList());
    }

    @NotNull
    private List<IBaseResource> loadExampleResources(NpmPackage npmPackage) {
        LOG.debug("Loading sample resources from " + npmPackage.name());
        var folder = npmPackage.getFolders().get(PACKAGE_EXAMPLE);

        if (!includeRoot)
            return getResources(npmPackage, folder);
        else
            return  Stream.concat(getResources(npmPackage, folder).stream(), getResources(npmPackage, npmPackage.getFolders().get(PACKAGE)).stream()).toList();
    }

    @NotNull
    private List<IBaseResource> getResources(NpmPackage npmPackage, NpmPackage.NpmPackageFolder folder) {
        if (folder == null) {
            LOG.debug("Found no example resources in " + npmPackage.name());
            return List.of();
        }

        List<String> fileNames;
        try {
          fileNames = folder.getTypes().values().stream().flatMap(Collection::stream).toList();
        } catch (IOException e) {
          throw new RuntimeException(e.getMessage(), e);
        }
        LOG.debug("Found " + fileNames.size() + " example resources in " + npmPackage.name());

        return fileNames.stream().map(fileName -> {
            try {
                return new String(folder.fetchFile(fileName));
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).map(fhirContext.newJsonParser().setSuppressNarratives(true)::parseResource).collect(Collectors.toList());
    }

    Function<String, NpmPackage> toNpmPackage = (nameAndVersion) -> {
        try {
            return packageManager.loadPackage(nameAndVersion);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    };

    Predicate<IBaseResource> isSearchBundle = (resource) -> {
        if (resource instanceof org.hl7.fhir.dstu2.model.Bundle) {
            return ((org.hl7.fhir.dstu2.model.Bundle) resource).getTypeElement().getValue() == org.hl7.fhir.dstu2.model.Bundle.BundleType.SEARCHSET;
        }
        if (resource instanceof org.hl7.fhir.dstu3.model.Bundle) {
            return ((org.hl7.fhir.dstu3.model.Bundle) resource).getTypeElement().getValue() == org.hl7.fhir.dstu3.model.Bundle.BundleType.SEARCHSET;
        }
        if (resource instanceof org.hl7.fhir.r4.model.Bundle) {
            return ((org.hl7.fhir.r4.model.Bundle) resource).getTypeElement().getValue() == org.hl7.fhir.r4.model.Bundle.BundleType.SEARCHSET;
        }
        if (resource instanceof org.hl7.fhir.r4b.model.Bundle) {
            return ((org.hl7.fhir.r4b.model.Bundle) resource).getTypeElement().getValue() == org.hl7.fhir.r4b.model.Bundle.BundleType.SEARCHSET;
        }
        if (resource instanceof org.hl7.fhir.r5.model.Bundle) {
            return ((org.hl7.fhir.r5.model.Bundle) resource).getTypeElement().getValue() == org.hl7.fhir.r5.model.Bundle.BundleType.SEARCHSET;
        }
        return false;
    };
}
