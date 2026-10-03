FROM haskell:9.6.7-slim-bullseye AS builder

ARG CARP_REF=1d36c69d83274dbe792b9a75ed8c766f75c33264

RUN git clone https://github.com/carp-lang/Carp.git /opt/carp \
    && git -C /opt/carp checkout "$CARP_REF" \
    && cd /opt/carp \
    && stack install --system-ghc --local-bin-path /usr/local/bin

RUN git config --global url."https://github.com/".insteadOf git@github.com:

WORKDIR /src
COPY main.carp posts.carp datastar.carp ./
ENV CARP_DIR=/opt/carp
RUN carp --eval-preload '(Project.config "compiler" "gcc -Wl,--no-as-needed")' main.carp --optimize -b

FROM debian:bookworm-slim

RUN useradd --create-home --uid 10001 app
WORKDIR /app
COPY --from=builder /src/out/persona-blog /app/persona-blog
COPY content/ /app/content/
COPY public/ /app/public/
RUN chown -R app:app /app

USER app
EXPOSE 8080
CMD ["/app/persona-blog"]
