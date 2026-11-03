FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /vault .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /vault /vault
USER 65532:65532
ENTRYPOINT ["/vault"]
