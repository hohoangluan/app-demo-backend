"""Shared behavior for deterministic contract-only FastAPI applications."""

from typing import Any

from fastapi import FastAPI


class ContractFastAPI(FastAPI):
    """FastAPI application that omits undocumented validation responses."""

    def openapi(self) -> dict[str, Any]:
        """Generate OpenAPI without FastAPI's implicit HTTP 422 surface."""
        schema = super().openapi()
        for path_item in schema.get("paths", {}).values():
            for operation in path_item.values():
                if isinstance(operation, dict):
                    responses = operation.get("responses")
                    if isinstance(responses, dict):
                        responses.pop("422", None)

        components = schema.get("components", {})
        schemas = components.get("schemas", {}) if isinstance(components, dict) else {}
        if isinstance(schemas, dict):
            schemas.pop("HTTPValidationError", None)
            schemas.pop("ValidationError", None)
        return schema
