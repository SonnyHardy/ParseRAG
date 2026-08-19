"""ParseRAG — parse a PDF from Python.

    pip install requests
    export API_KEY="your-api-key"
    python example.py document.pdf

BASE_URL defaults to a local run; point it at your deployment.
"""

import os
import sys
import time

import requests

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
API_KEY = os.environ["API_KEY"]

# Parsing is synchronous and a large scanned document goes through a vision model
# page by page, so the read timeout has to be generous.
TIMEOUT = (10, 300)  # (connect, read) seconds


def parse(path: str, max_retries: int = 3) -> dict:
    """Parse a PDF and return the response payload.

    Retries on 429 only: the server tells us how long to wait in Retry-After.
    Other errors are final and raise, carrying the API error code.
    """
    for attempt in range(max_retries):
        with open(path, "rb") as f:
            response = requests.post(
                f"{BASE_URL}/api/v1/parse",
                headers={"X-API-Key": API_KEY},
                files={"file": (os.path.basename(path), f, "application/pdf")},
                timeout=TIMEOUT,
            )

        remaining = response.headers.get("X-RateLimit-Remaining")
        if remaining is not None:
            print(f"[rate limit] {remaining} requests left this minute", file=sys.stderr)

        if response.status_code == 429:
            body = response.json()
            # Two different situations share the 429: a rate limit clears within the
            # minute, a monthly quota does not — retrying the latter is pointless.
            if body.get("error") == "QUOTA_EXCEEDED":
                raise RuntimeError(f"QUOTA_EXCEEDED: {body.get('message')}")

            wait = int(response.headers.get("Retry-After", "60"))
            print(f"[rate limit] hit, waiting {wait}s", file=sys.stderr)
            time.sleep(wait)
            continue

        if not response.ok:
            body = response.json()
            raise RuntimeError(f"{body.get('error')}: {body.get('message')}")

        return response.json()

    raise RuntimeError(f"Still rate limited after {max_retries} attempts")


def usage() -> dict:
    """Current cycle consumption. Readable even once the quota is exhausted."""
    response = requests.get(
        f"{BASE_URL}/api/v1/usage",
        headers={"X-API-Key": API_KEY},
        timeout=TIMEOUT,
    )
    response.raise_for_status()
    return response.json()


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("Usage: python example.py <file.pdf>")

    result = parse(sys.argv[1])

    print(f"{result['document_id']} — {result['pages']} pages, "
          f"language {result['language']}, {result['processing_ms']} ms")

    for chunk in result["chunks"]:
        # Chunks flagged for review came out of a page the extractor is unsure about:
        # index them separately, or not at all.
        flag = " [review]" if chunk["manual_review_needed"] else ""
        preview = chunk["text"][:80].replace("\n", " ")
        print(f"  p{chunk['page']:>3} {chunk['type']:<15} "
              f"conf={chunk['confidence']:.2f}{flag}  {preview}")

        if chunk["type"] == "TABLE":
            table = chunk["table_json"]
            print(f"       table: {len(table['rows'])} rows × {len(table['headers'])} columns")

    reviewed = sum(1 for c in result["chunks"] if c["manual_review_needed"])
    print(f"\n{len(result['chunks'])} chunks, {reviewed} flagged for manual review")

    quota = usage()
    print(f"Quota: {quota['docs_used']}/{quota['docs_limit']} documents "
          f"({quota['plan']} plan), resets {quota['reset_date']}")


if __name__ == "__main__":
    main()
