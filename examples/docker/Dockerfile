FROM alpine:3.22@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f
WORKDIR /examples
COPY --chmod=0555 verify.sh ./verify.sh
USER 65534:65534
HEALTHCHECK NONE
ENTRYPOINT ["/bin/sh", "/examples/verify.sh"]
CMD ["plain"]