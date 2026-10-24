FROM alpine:3.22
WORKDIR /examples
COPY --chmod=0555 verify.sh ./verify.sh
USER 65534:65534
HEALTHCHECK NONE
ENTRYPOINT ["/bin/sh", "/examples/verify.sh"]
CMD ["plain"]