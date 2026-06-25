# Native real-HTTP regression guard for #594

This file is **documentation for the maintainer**, not a workflow. Per `AGENTS.md`, an agent
must not edit `.github/workflows/`; the snippet below is authored here and integrated into the
CI workflow by a human.

## Why this guard exists

[#594](https://github.com/naftiko/ikanos/issues/594): the GraalVM native binary **bound the
TCP socket but hung on every HTTP request** — conditional Jetty/Restlet reflection classes
(`NativePRNG`, `SecurityUtils`, `ForwardedRequestCustomizer$Forwarded`, …) were missing from
`reflect-config.json`, so the request-handling pipeline died silently on the first request.

The existing [#581](https://github.com/naftiko/ikanos/issues/581) native guard in the
`test-binary` job of `.github/workflows/publish-cli-bin.yml` is **TCP-listen only**
(`exec 3<>"/dev/tcp/127.0.0.1/$REST_PORT"`). It is *structurally blind* to #594: the socket
**does** listen; only request handling dies. A guard that catches #594 must issue a **real
HTTP GET** on the served endpoint and assert a `200` + body.

## What to add

Add the step below to the `test-binary` job in `.github/workflows/publish-cli-bin.yml`,
**after** the existing `Native regression — serve (… exposed port listens)` step (it reuses
the same `BIN` resolution pattern). It uses the dedicated, backend-free fixture
`serve-rest-mock.ikanos.yaml` (port `9620`, `GET /greet` → `Hello from native mock!`) created
for this issue.

```yaml
      - name: Native regression — serve REST + real HTTP GET returns 200 (#594)
        shell: bash
        run: |
          BIN="./bin/${{ matrix.artifact_name }}"
          FIXTURE="ikanos-cli/src/test/resources/native-regression/serve-rest-mock.ikanos.yaml"
          REST_PORT=9620

          "$BIN" serve "$FIXTURE" > serve-rest-mock.log 2>&1 &
          SERVE_PID=$!

          # Wait for the exposed REST port to start listening (serve may crash early
          # on a broken reflect-config, so bail out if the process is gone).
          LISTENING=0
          for i in $(seq 1 20); do
            if (exec 3<>"/dev/tcp/127.0.0.1/$REST_PORT") 2>/dev/null; then
              exec 3>&- 3<&- 2>/dev/null || true
              LISTENING=1
              break
            fi
            kill -0 "$SERVE_PID" 2>/dev/null || break
            sleep 1
          done

          # Issue a REAL HTTP GET — this is the part the #581 TCP probe cannot do.
          # #594 manifested as: socket listening, but every GET hangs. Use a short
          # --max-time so a regression fails fast instead of stalling the job.
          BODY=""
          STATUS=000
          if [ "$LISTENING" -eq 1 ]; then
            STATUS=$(curl -s -o resp-body.txt -w '%{http_code}' --max-time 15 \
              "http://127.0.0.1:$REST_PORT/greet" || echo 000)
            BODY="$(cat resp-body.txt 2>/dev/null || true)"
          fi

          kill "$SERVE_PID" 2>/dev/null || true

          echo "----- serve-rest-mock.log -----"; cat serve-rest-mock.log
          echo "----- HTTP status: $STATUS -----"
          echo "----- HTTP body:   $BODY -----"

          if grep -E "ClassNotFoundException|NoSuchMethodException|ExceptionInInitializerError|No available server connector" serve-rest-mock.log; then
            echo "FAIL: native reflection regression on serve (#594)"
            exit 1
          fi
          if [ "$LISTENING" -ne 1 ]; then
            echo "FAIL: exposed REST port $REST_PORT never started listening — HTTP connector did not boot (#594)"
            exit 1
          fi
          if [ "$STATUS" != "200" ]; then
            echo "FAIL: GET /greet returned HTTP $STATUS, expected 200 — native HTTP request handling is mute (#594)"
            exit 1
          fi
          if ! printf '%s' "$BODY" | grep -q "Hello from native mock!"; then
            echo "FAIL: GET /greet body did not contain the expected greeting (#594)"
            exit 1
          fi
          echo "PASS: native binary served a real HTTP GET with status 200 and the expected body (#594)"
```

## Notes

- **Why a dedicated fixture and not the #581 `serve-http.ikanos.yaml`?** `serve-rest-mock`
  is the *smallest* REST-only surface (one adapter, one static-value operation, no backend,
  no skill/control port), so a failure points unambiguously at the native HTTP request path
  rather than at any extra layer. It also has no upstream fan-out, so the GET is deterministic
  in CI.
- **`--max-time 15`**: #594's symptom was a hang (~12 s client timeout), so a bounded curl
  makes a regression fail fast and visibly rather than stalling the runner.
- **Cross-platform**: the `test-binary` job already runs these steps with `shell: bash` on all
  matrix OSes; `curl` and bash `/dev/tcp` are available there, matching the existing #581 step.
- Once integrated and green, this guard closes the last open item on #594.
