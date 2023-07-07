# Monkey Projects OCI Object Storage

This is a Clojure library to access the [Oracle OCI Object Storage API](https://docs.oracle.com/en-us/iaas/Content/Object/home.htm).  You could of course use the OCI provided Java lib instead,
but I've found that it's pretty cumbersome to use.  Also, since it automatically
marshalls everything into Java POJOs, it's not very efficient.  The Clojure way
is to simply process the incoming objects as a data structure.

Another reason why I wrote this is because I want to use it in a GraalVM native
image, and the Java lib simply has too many dependencies which make it difficult
to build a native image and it would be bloated anyway.

## Structure

The lib provides two layers to access the API.  One is the low-level access,
that is just a thin layer on top of the REST calls.  On top of that there is
a layer that provides convenience functions for often-used scenarios.

## Usage

[![Clojars Project](https://img.shields.io/clojars/v/com.monkeyprojects/oci-os.svg)](https://clojars.org/com.monkeyprojects/oci-os)
[![CircleCI](https://circleci.com/gh/monkey-projects/oci-os.svg?style=svg)](https://app.circleci.com/pipelines/github/monkey-projects/oci-os)

Include the library in your project:
```clojure
{:deps {monkey/oci-os {:mvn/version ..latest..}}}
```

Then include the namespace and create a context:
```clojure
(require '[monkey.oci.os.core :as os])

;; Configuration, must contain the necessary properties to connect,
;; see oci-sign for that
(def config {:user-ocid ... }
(def ctx (os/make-context config))

;; Now you can make requests
@(os/get-namespace ctx)  ; Returns the bucket namespace
```

In order to gain access, you must provide the necessary configuration.  This
is then used to sign the request.  See the [oci-sign library](https://github.com/monkey-projects/oci-sign)
for details.

## Copyright

Copyright (c) 2023 by Monkey Projects BV.