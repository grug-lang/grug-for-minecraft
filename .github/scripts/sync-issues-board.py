#!/usr/bin/env python3
"""Adds every issue (never a pull request) of the org's public, non-archived repositories to the Issues Board.

Usage: sync-issues-board.py [--dry-run] [--existing-items FILE]

Reads GH_TOKEN through `gh`. Any failed API call raises, which fails the run.
--dry-run never mutates. --existing-items FILE replaces the board read with a JSON list of
{"type": "Issue", "id": "<node id>"} objects, for a dry run without Projects read access.
"""

import json
import subprocess
import sys
import time

ORG = "grug-lang"
PROJECT_ID = "PVT_kwDODhg01s4BT-Og"
STATUS_FIELD_ID = "PVTSSF_lADODhg01s4BT-OgzhBJgA0"
STATUS_TODO = "f75ad846"
STATUS_DONE = "98236657"
# GitHub asks for at least a second between mutations to stay clear of the secondary rate limit.
MUTATION_PAUSE = 1.0


def graphql(query, **variables):
    args = ["gh", "api", "graphql", "-f", "query=" + query]
    for key, value in variables.items():
        args += ["-f", f"{key}={value}"]
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"gh api graphql failed: {result.stderr.strip()}\n{result.stdout.strip()}")
    data = json.loads(result.stdout)
    if data.get("errors"):
        raise SystemExit(f"GraphQL errors: {json.dumps(data['errors'])}")
    return data["data"]


def paginate(query, path, **variables):
    """Yields every node of the connection at `path`, following pageInfo."""
    cursor = ""
    while True:
        args = dict(variables)
        if cursor:
            args["cursor"] = cursor
        node = graphql(query, **args)
        for key in path:
            node = node[key]
        yield from node["nodes"]
        if not node["pageInfo"]["hasNextPage"]:
            return
        cursor = node["pageInfo"]["endCursor"]


def repositories():
    query = """query($org: String!, $cursor: String) {
      organization(login: $org) {
        repositories(first: 100, after: $cursor, privacy: PUBLIC, isArchived: false) {
          nodes { name }
          pageInfo { hasNextPage endCursor }
        }
      }
    }"""
    return [r["name"] for r in paginate(query, ["organization", "repositories"], org=ORG)]


def issues(repo):
    query = """query($org: String!, $repo: String!, $cursor: String) {
      repository(owner: $org, name: $repo) {
        issues(first: 100, after: $cursor) {
          nodes { id number state url }
          pageInfo { hasNextPage endCursor }
        }
      }
    }"""
    return list(paginate(query, ["repository", "issues"], org=ORG, repo=repo))


def board_items():
    query = """query($project: ID!, $cursor: String) {
      node(id: $project) {
        ... on ProjectV2 {
          items(first: 100, after: $cursor) {
            nodes { content { __typename ... on Issue { id } ... on PullRequest { id } } }
            pageInfo { hasNextPage endCursor }
          }
        }
      }
    }"""
    items = []
    for item in paginate(query, ["node", "items"], project=PROJECT_ID):
        content = item["content"]
        if content and "id" in content:
            items.append({"type": content["__typename"], "id": content["id"]})
    return items


def add_item(content_id):
    data = graphql(
        """mutation($project: ID!, $content: ID!) {
          addProjectV2ItemById(input: {projectId: $project, contentId: $content}) { item { id } }
        }""",
        project=PROJECT_ID,
        content=content_id,
    )
    return data["addProjectV2ItemById"]["item"]["id"]


def set_status(item_id, option_id):
    graphql(
        """mutation($project: ID!, $item: ID!, $field: ID!, $option: String!) {
          updateProjectV2ItemFieldValue(input: {projectId: $project, itemId: $item, fieldId: $field,
            value: {singleSelectOptionId: $option}}) { projectV2Item { id } }
        }""",
        project=PROJECT_ID,
        item=item_id,
        field=STATUS_FIELD_ID,
        option=option_id,
    )


def main(argv):
    dry_run = "--dry-run" in argv
    existing_file = argv[argv.index("--existing-items") + 1] if "--existing-items" in argv else None

    if existing_file:
        with open(existing_file) as f:
            existing = json.load(f)
    else:
        existing = board_items()
    on_board = {item["id"] for item in existing}
    # Pull requests are synced only while the board already holds some, so the board keeps its shape.
    include_prs = any(item["type"] == "PullRequest" for item in existing)
    print(
        f"board holds {len(existing)} items, pull requests {'included' if include_prs else 'left out'}"
    )

    repos = repositories()
    print(f"{len(repos)} public non-archived repositories: {', '.join(repos)}")

    missing = []
    for repo in repos:
        for node in issues(repo):
            if node["id"] not in on_board:
                missing.append(node)
    print(f"{len(missing)} items missing from the board")

    for node in missing:
        status = STATUS_TODO if node["state"] == "OPEN" else STATUS_DONE
        label = "Todo" if status == STATUS_TODO else "Done"
        if dry_run:
            print(f"would add {node['url']} as {label}")
            continue
        item_id = add_item(node["id"])
        set_status(item_id, status)
        print(f"added {node['url']} as {label}")
        time.sleep(MUTATION_PAUSE)


if __name__ == "__main__":
    main(sys.argv[1:])
