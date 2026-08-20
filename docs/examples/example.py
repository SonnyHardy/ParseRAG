"""ParseRAG — parse a PDF from Python.

    pip install requests
    export API_KEY="your-rapidapi-key"
    python example.py document.pdf

BASE_URL defaults to a local run, which expects the self-hosted X-API-Key header.
Through the marketplace: BASE_URL=https://parserag.p.rapidapi.com.
"""

import os
import sys
import time

import requests

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
API_KEY = os.environ["API_KEY"]

# Through RapidAPI the key travels as X-RapidAPI-Key; a self-hosted instance expects X-API-Key.
KEY_HEADER = "X-RapidAPI-Key" if "rapidapi.com" in BASE_URL else "X-API-Key"
HEADERS = {KEY_HEADER: API_KEY}

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
                headers=HEADERS,
                files={"file": (os.path.basename(path), f, "application/pdf")},
                timeout=TIMEOUT,
            )

        remaining = response.headers.get("x-ratelimit-requests-remaining")
        if remaining is not None:
            print(f"[rate limit] {remaining} requests left this minute", file=sys.stderr)

        if response.status_code == 429:
            # Quota exhausted (from the marketplace) or traffic guard (from ParseRAG). Only the
            # second is worth retrying, and it tells you how long to wait.
            wait = int(response.headers.get("Retry-After", "0"))
            if wait == 0:
                raise RuntimeError("Quota exhausted — check your plan on RapidAPI")

            print(f"[rate limit] hit, waiting {wait}s", file=sys.stderr)
            time.sleep(wait)
            continue

        if not response.ok:
            body = response.json()
            raise RuntimeError(f"{body.get('error')}: {body.get('message')}")

        return response.json()

    raise RuntimeError(f"Still rate limited after {max_retries} attempts")


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



if __name__ == "__main__":
    main()
