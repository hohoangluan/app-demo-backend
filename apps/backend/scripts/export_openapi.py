"""Export or verify generated Public and Device OpenAPI artifacts."""

import sys
from collections.abc import Sequence
from pathlib import Path

import yaml

from app.contract_api import create_device_contract_app, create_public_contract_app

PUBLIC_ARTIFACT = "public-api.openapi.yaml"
DEVICE_ARTIFACT = "device-api.openapi.yaml"


def render_contracts() -> dict[str, str]:
    """Render both isolated contract applications as deterministic YAML."""
    schemas = {
        PUBLIC_ARTIFACT: create_public_contract_app().openapi(),
        DEVICE_ARTIFACT: create_device_contract_app().openapi(),
    }
    return {
        filename: yaml.safe_dump(
            schema,
            allow_unicode=True,
            default_flow_style=False,
            sort_keys=False,
            width=100,
        )
        for filename, schema in schemas.items()
    }


def write_contracts(output_directory: Path) -> tuple[Path, ...]:
    """Write both generated contract artifacts."""
    output_directory.mkdir(parents=True, exist_ok=True)
    rendered = render_contracts()
    paths = tuple(output_directory / filename for filename in rendered)
    for path in paths:
        path.write_text(rendered[path.name], encoding="utf-8", newline="\n")
    return paths


def find_stale_contracts(output_directory: Path) -> tuple[Path, ...]:
    """Return missing or out-of-date generated artifacts."""
    rendered = render_contracts()
    return tuple(
        path
        for filename, expected in rendered.items()
        if not (path := output_directory / filename).is_file()
        or path.read_text(encoding="utf-8") != expected
    )


def main(argv: Sequence[str] | None = None) -> int:
    """Export artifacts, or return non-zero when --check finds drift."""
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments not in ([], ["--check"]):
        sys.stderr.write("usage: export_openapi.py [--check]\n")
        return 2

    output_directory = Path(__file__).resolve().parents[3] / "contracts"
    if arguments == ["--check"]:
        stale_paths = find_stale_contracts(output_directory)
        if stale_paths:
            names = ", ".join(path.name for path in stale_paths)
            sys.stderr.write(f"OpenAPI artifacts are stale: {names}\n")
            return 1
        sys.stdout.write("OpenAPI artifacts are up to date.\n")
        return 0

    written_paths = write_contracts(output_directory)
    names = ", ".join(path.name for path in written_paths)
    sys.stdout.write(f"Wrote OpenAPI artifacts: {names}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
