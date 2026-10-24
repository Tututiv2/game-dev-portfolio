from __future__ import annotations

import os

from app.providers.base import I2VProvider, T2IProvider
from app.providers.mock import MockI2VProvider, MockT2IProvider


def get_t2i_provider() -> T2IProvider:
    """Resolve the T2I provider from T2I_PROVIDER.

    "local" runs Stable Diffusion on this machine's GPU: free, offline, and the best
    fit for pixel art (community sprite checkpoints are not offered by hosted APIs).
    It is the recommended setting. "replicate" is a hosted, pay-per-image alternative.
    Anything else -- including a typo -- degrades to the mock placeholder provider
    rather than raising, so a bad value cannot break every job.

    Imports are deliberately inside the branches: torch/diffusers take seconds to
    import and must not be paid for by a server that is running in mock mode.
    """
    provider_name = os.getenv("T2I_PROVIDER", "mock").strip().lower()
    if provider_name in {"local", "diffusers", "local-diffusion"}:
        from app.providers.local_diffusion import LocalDiffusionT2IProvider

        return LocalDiffusionT2IProvider()
    if provider_name == "replicate":
        from app.providers.replicate import ReplicateT2IProvider

        return ReplicateT2IProvider()
    return MockT2IProvider()


def get_i2v_provider() -> I2VProvider:
    provider_name = os.getenv("I2V_PROVIDER", "mock").strip().lower()
    if provider_name == "mock":
        return MockI2VProvider()
    return MockI2VProvider()
