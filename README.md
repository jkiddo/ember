# ember
A tool for building FHIR transaction bundles based on examlpe instances in designated FHIR IG's.

You can run it using eg. ```docker run jkiddo/ember --packageId=ch.fhir.ig.ch-core#2.0.0 ```

There are 6 parameters: ```directory```, ```serverBase```, ```packageId```, ```includeSearchBundles```, ```location``` and ```loadRecursively```. If ```serverbase``` is omitted, the total bundle transaction is printed in the terminal - if provided it will send the bundle transaction to the server address. The ```packageId``` is the IG id and version. ```loadRecursively``` is by default false - if set to true, all example instances from parent dependencies recursively are also included in the total transaction bundle. By default search bundles are not included - they can be by setting ```includeSearchBundles``` to true. ```directory``` (only works with FHIR R5) can be used if you just wan't to  scrape a directory of FHIR resources and combine them to a FHIR transaction bundle. 

The ```location``` parameter can be used to point to a local package using ```file:/...``` or a remote package using ```http://...```. If doing so, do omit the use of ```packageId```. The package will automatically be fetched and installed into the local fhir cache. The local FHIR cache can be mapped into the image (when using Docker) like the following:

Mapping the local FHIR into the image and fetching the package using HTTP 
```
docker run -v /Users/jkv/.fhir:/home/nonroot/.fhir ember --location=https://build.fhir.org/ig/hl7-eu/gravitate-health/package.tgz
```