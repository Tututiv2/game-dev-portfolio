from __future__ import annotations

from app.providers.base import I2VOutput, T2IOutput


class MockT2IProvider:
    name = "mock-t2i"

    def generate(self, request: object, *, job_id: str) -> T2IOutput:
        return T2IOutput(
            provider=self.name,
            url=f"https://picsum.photos/seed/{job_id}/512/512",
            mime_type="image/png",
            width=512,
            height=512,
            metadata={
                "job_seed": job_id,
            },
        )


class MockI2VProvider:
    name = "mock-i2v"

    def generate(self, request: object, *, job_id: str) -> I2VOutput:
        duration_seconds = getattr(request, "duration_seconds", 3)
        return I2VOutput(
            provider=self.name,
            url="https://samplelib.com/lib/preview/mp4/sample-5s.mp4",
            mime_type="video/mp4",
            width=1024,
            height=1024,
            duration_ms=int(duration_seconds) * 1000,
            metadata={
                "job_seed": job_id,
            },
        )
