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

Under the hood it uses [Martian](https://github.com/oliyh/martian) to send HTTP
requests to the OCI API.  We're using the [Httpkit](https://github.com/http-kit/http-kit)
plugin, because that's a lib that is as "pure" Clojure as possible with almost no
external dependencies.

## Usage

[![Clojars Project](https://img.shields.io/clojars/v/com.monkeyprojects/oci-os.svg)](https://clojars.org/com.monkeyprojects/oci-os)
[![CircleCI](https://circleci.com/gh/monkey-projects/oci-os.svg?style=svg)](https://app.circleci.com/pipelines/github/monkey-projects/oci-os)

Include the library in your project:
```clojure
{:deps {com.monkeyprojects/oci-os {:mvn/version ..latest..}}}
```

Then include the namespace and create a context:
```clojure
(require '[monkey.oci.os.core :as os])

;; Configuration, must contain the necessary properties to connect,
;; see oci-sign for that
(def config {:user-ocid ... }
(def ctx (os/make-client config))

;; Now you can make requests
(def bucket-ns @(os/get-namespace ctx))  ; Returns the bucket namespace
@(os/list-objects ctx {:ns bucket-ns :bucket-name "my-bucket"}) ; Lists bucket objects
```

In order to gain access, you must provide the necessary configuration.  This
is then used to sign the request.  See the [oci-sign library](https://github.com/monkey-projects/oci-sign)
for details, but you will need your tenancy OCID, user OCID, private key, key fingerprint
and the region you're targeting.

The functions in the `core` namespace will unwrap the response by default, returning the
response body on success.  If there is a failure, an exception will be thrown, with the
full response in the `ex-data`.  See below on how to send requests and gain access to
the raw response map.

### Uploading Files

Creating or updating files is done with the `put-object` function.  The options map should
contain the `:ns` (namespace), `:bucket-name`, `:object-name` and `:contents` values.  The
contents is a string with the file contents.  Request signature require calculating an SHA256
hash for the body, so streaming is not supported.  For larger files, you should use multipart
requests (coming later).

The upload and download requests don't produce JSON so the calls return the underlying
HttpKit response, which contains also a `:body` value.
```clojure
@(os/put-object ctx {:ns "..." :bucket-name "test-bucket" :object-name "test.txt" :contents "this is a test file"})
;; This will return an empty string on success

# Now you can download the file as well
@(os/get-object {:ns "..." :bucket-name "test-bucket" :object-name "test.txt"})
;; Returns the file contents from the :body.  Depending on the content type,
;; this can be a string or an input stream.
```

By default the `Content-Type` is `application/octet-stream`.  But you can override this by
specifying the raw header in the request options:
```clojure
@(os/put-object {:ns "..."
                 ...
		 :contents "File contents"
		 :martian.core/request {:headers {"content-type" "text.plain"}}})
```
This will explicitly pass in the `Content-Type` header to the backend, which will also
be returned when you download the file.

### Low-level Calls

Should you need access to the full response, for example to read certain headers like `ETag`,
you can send requests using the lower-level `monkey.oci.os.martian` namespace.  These contain
about the same functions (one for every defined route), but they won't interpret the response,
and instead return the full response map.

```clojure
(require '[monkey.oci.os.martian :as m])

@(m/head-object {:ns ...})
;; This will return the full response, with :headers to inspect, etc...
```

This allows you to have more control over how requests are handled.  This can also be useful
should you want to handle 'expected' 4xx responses, instead of catching exceptions (which is
bad form if you're actually expecting it to happen, right?)

## TODO

 - Take care of pagination.
 - Provide functionality for handling multipart uploads for large files.
 - Add something that automagically generates the Martian routes from the OCI provided Java libs.
   (Or find the OpenAPI specs.)

## Copyright

Copyright (c) 2023 by Monkey Projects BV.