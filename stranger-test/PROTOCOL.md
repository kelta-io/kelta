# Stranger test protocol

How to run the K-3 stranger test described in [README.md](README.md). Follow it exactly;
a run that deviates from it does not count.

## Who qualifies

- Has never seen this repository, its docs site or a Kelta demo, and has not worked on Kelta.
- Has Docker installed and running (Docker Desktop, or Docker Engine with the Compose v2.24+
  plugin), plus `make` and `git`. Installing Docker is not part of the test.
- Is comfortable in a terminal. They need not know Java, Node or Kelta.
- Agrees in advance to the recording and redaction rules below.

## The handoff

Give the participant **only** this URL and nothing else — no verbal summary, no goal beyond
"follow the quickstart until you have created your first collection":

    https://github.com/kelta-io/kelta#quickstart

They may read anything reachable from that page. Do not send links, screenshots or a
pre-cloned checkout.

## Observer rules

- Do not help. No hints, no pointing at the screen, no answering questions about the steps.
  If the participant asks, say "do whatever you would do if I weren't here."
- Note the wall-clock start time when they open the URL.
- Timestamp every stuck point: each time they pause for more than about 30 seconds, re-read a
  step, search the web, retry a command or say they are unsure. Write the time, what they were
  trying to do, and what they did next.
- Stop only when they have created a collection, or they give up, or 60 minutes pass.
- Any intervention ends the unaided attempt. Record when and why; the run then fails.

## Recording

Record the participant's terminal with `script`. The log belongs in this directory as

    stranger-test/runs/<YYYY-MM-DD>-<initials>.log

but the participant has no checkout yet, so before handing over, open a terminal for them in
their home directory and create the same path there (`cd ~ && mkdir -p stranger-test/runs`).
Then start the recording; the participant works inside it:

macOS (BSD `script`):

```bash
script -q stranger-test/runs/2026-10-20-ab.log
```

Linux (util-linux `script`, flushing as it goes):

```bash
script -q -f stranger-test/runs/2026-10-20-ab.log
```

End the recording with `exit`, then copy the log into `stranger-test/runs/` in the
observer's own checkout.

At the top of the observer notes, record:

- OS and version (`sw_vers` on macOS, `cat /etc/os-release` on Linux)
- Docker version (`docker version` and `docker compose version`)
- Memory allocated to Docker (`docker info --format '{{.MemTotal}}'`)

Keep the observer notes (start time, stuck points, end time) beside the log as
`stranger-test/runs/<YYYY-MM-DD>-<initials>.md`.

## Pass criteria

The run passes only if:

- the participant creates their first collection in the UI,
- in one attempt, with no intervention from the observer, and
- the total wall-clock time from opening the URL to the created collection is recorded.

Any other outcome is a fail. Record it anyway: the stuck points are the fixes to make
before the next run.

## Redaction and consent

This repository is public. Before committing anything from a run:

- Remove the first-boot admin password kelta-auth printed, and any password the participant
  chose, from the log and notes.
- Remove personal data: names beyond initials, usernames, hostnames, home-directory paths,
  email addresses, IP addresses, tokens.
- Show the participant the redacted files and get their explicit consent to publish them.
  Without consent, do not commit the run; record only the pass/fail and wall-clock time.

Then update the status in [README.md](README.md) with the date, result and a link to the run.
