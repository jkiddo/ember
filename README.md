# ember
A tool for building FHIR transaction bundles based on examlpe instances in designated FHIR IG's.

You can run it using eg. ```docker run jkiddo/ember --igIdAndVersion=ch.fhir.ig.ch-core#2.0.0 ```

There are 4 parameters: ```serverBase```, ```igIdAndVersion``` (mandatory), ```includeSearchBundles``` and ```loadRecursively```. If ```serverbase``` is omitted, the total bundle transaction is printed in the terminal - if provided it will send the bundle transaction to the server address. The ```igIdAndVersion``` is the IG id and version. ```loadRecursively``` is by default false - if set to true, all example instances from parent dependencies recursively are also included in the total transaction bundle. By default search bundles are not included - they can be by setting ```includeSearchBundles``` to true.
