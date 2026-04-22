import glob
import json
import os
import time

import yt_dlp


CACHE_TTL_SEC = 15 * 60
NON_CACHEABLE_LIVE_STATUSES = {"is_live", "is_upcoming", "was_live"}


def _extract_video_info(source_url: str) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        return ydl.extract_info(source_url, download=False)


def download_audio_file(source_url: str, output_dir: str) -> str:
    os.makedirs(output_dir, exist_ok=True)
    info = _extract_video_info(source_url)
    video_id = info.get("id")
    live_status = info.get("live_status")

    if video_id:
        pattern = os.path.join(output_dir, f"{video_id}-*.*")
        for candidate in sorted(glob.glob(pattern), key=lambda p: os.path.getmtime(p), reverse=True):
            if not os.path.isfile(candidate):
                continue
            age_sec = int(time.time() - os.path.getmtime(candidate))
            if age_sec <= CACHE_TTL_SEC and live_status not in NON_CACHEABLE_LIVE_STATUSES:
                return json.dumps(
                    {
                        "path": candidate,
                        "fromCache": True,
                        "cacheAgeSec": age_sec,
                        "liveStatus": live_status,
                    }
                )

    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": "bestaudio/best",
        "outtmpl": os.path.join(output_dir, "%(id)s-%(format_id)s.%(ext)s"),
        "overwrites": False,
        "continuedl": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        final_info = ydl.extract_info(source_url, download=True)
        path = ydl.prepare_filename(final_info)

    return json.dumps(
        {
            "path": path,
            "fromCache": False,
            "cacheAgeSec": 0,
            "liveStatus": final_info.get("live_status"),
        }
    )
