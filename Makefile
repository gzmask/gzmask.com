CARP ?= carp
SOURCE := main.carp
BINARY := out/persona-blog

.PHONY: run build release check test import clean

run:
	$(CARP) $(SOURCE) -x

build:
	$(CARP) $(SOURCE) -b

release:
	$(CARP) $(SOURCE) --optimize -b

check:
	$(CARP) $(SOURCE) --check

test: check release
	bb scripts/smoke-test.bb

import:
	bb import-archive

clean:
	rm -rf out
