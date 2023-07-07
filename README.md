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

## Copyright

Copyright (c) 2023 by Monkey Projects BV.