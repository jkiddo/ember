# ember
A tool for building FHIR transaction bundles based on example instances in designated FHIR IG's. This makes it easy to bootstrap or load existing FHIR servers that supports transaction bundles with test/example data from IG's. Where ember solely focuses on example resources the tool at https://github.com/brianpos/UploadFIG also handles other scenarios.

## usage
There are 6 parameters: 

- ```directory```: (only works with FHIR R5 - optional) can be used if you just wan't to  scrape a directory of FHIR resources and combine them to a FHIR transaction bundle.
- ```serverBase```: (optional) If ```serverbase``` is omitted, the total bundle transaction is printed in the terminal aka. a "dry-run". If ```serverbase``` is provided it will send the bundle transaction to the server address.
- ```packageId```: The ```packageId``` is the IG id and version.
- ```includeSearchBundles```: (optional) By default search bundles are not included - they can be by setting ```includeSearchBundles``` to true.
- ```location```: The ```location``` parameter can be used to point to a local package using ```file:/...``` or a remote package using ```http://...```. If doing so, do omit the use of ```packageId```. The package will automatically be fetched and installed into the local fhir cache. The local FHIR cache can be mapped into the image (when using Docker) 
- ```loadRecursively```: (optional)```loadRecursively``` is by default false - if set to true, all example instances from parent IG's/dependencies recursively are also included in the total transaction bundle.
- ```docsAndLists```: Only look for Bundles and Lists. Useful for Document heavy IG's like https://build.fhir.org/ig/hl7-eu/gravitate-health/toc.html
- ```usePUT```: Make the transaction entries as PUT instead of POST.


## example
You can run it using eg:
```
docker run jkiddo/ember --packageId=ch.fhir.ig.ch-core#2.0.0
```
or with the existing local FHIR cache (Mapping the local FHIR into the image and fetching the package using HTTP ):
```
docker run -v /Users/jkv/.fhir:/home/nonroot/.fhir jkiddo/ember --location=https://build.fhir.org/ig/hl7-eu/gravitate-health/package.tgz
```
## Build
Do `docker buildx build --platform=linux/arm64,linux/amd64 . --push -t jkiddo/ember:x.y.z`

## Docker location
See https://hub.docker.com/repository/docker/jkiddo/ember/general
