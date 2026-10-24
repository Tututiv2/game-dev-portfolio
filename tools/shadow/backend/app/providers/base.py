from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


@dataclass
class T2IOutput:
    provider: str
    url: str
    mime_type: str
    width: int
    height: int
    metadata: dict[str, object]


@dataclass
class I2VOutput:
    provider: str
    url: str
    mime_type: str
    width: int
    height: int
    duration_ms: int
    metadata: dict[str, object]


class T2IProvider(Protocol):
    name: str

    def generate(self, request: Any, *, job_id: str) -> T2IOutput:
        ...


class I2VProvider(Protocol):
    name: str

    def generate(self, request: Any, *, job_id: str) -> I2VOutput:
        ...
