import glob
import json
import os
import time

import yt_dlp


CACHE_TTL_SEC = 15 * 60
NON_CACHEABLE_LIVE_STATUSES = {"is_live", "is_upcoming", "was_live"}
TEMP_EXTENSIONS = {".ytdl", ".part", ".tmp"}
ALLOWED_EXTENSIONS = {".webm", ".m4a", ".mp4", ".opus", ".ogg", ".mp3", ".wav", ".mka"}
MIN_VALID_SIZE_BYTES = 1 * 1024 * 1024


def _extract_video_info(source_url: str) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        return ydl.extract_info(source_url, download=False)


def _validate_local_audio_file(path: str) -> str | None:
    if not os.path.isfile(path):
        return "not_file"
    lower = path.lower()
    for ext in TEMP_EXTENSIONS:
        if lower.endswith(ext):
            return f"temp_ext:{ext}"
    _, ext = os.path.splitext(lower)
    if ext not in ALLOWED_EXTENSIONS:
        return f"not_allowed_ext:{ext or 'none'}"
    size = os.path.getsize(path)
    if size < MIN_VALID_SIZE_BYTES:
        return f"too_small:{size}"
    return None


def download_audio_file(source_url: str, output_dir: str, force_download: bool = False) -> str:
    os.makedirs(output_dir, exist_ok=True)
    info = _extract_video_info(source_url)
    video_id = info.get("id")
    live_status = info.get("live_status")

    if video_id and not force_download:
        pattern = os.path.join(output_dir, f"{video_id}-*.*")
        for candidate in sorted(glob.glob(pattern), key=lambda p: os.path.getmtime(p), reverse=True):
            if not os.path.isfile(candidate):
                continue
            age_sec = int(time.time() - os.path.getmtime(candidate))
            if age_sec <= CACHE_TTL_SEC and live_status not in NON_CACHEABLE_LIVE_STATUSES:
                reject_reason = _validate_local_audio_file(candidate)
                if reject_reason is not None:
                    print(f"cacheCandidateRejected path={candidate} reason={reject_reason}")
                    continue
                print(f"finalSource fromCache=1 forceDownload=0 path={candidate}")
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
    reject_reason = _validate_local_audio_file(path)
    if reject_reason is not None:
        raise RuntimeError(f"Downloaded file validation failed: {reject_reason} path={path}")
    print(f"finalSource fromCache=0 forceDownload={1 if force_download else 0} path={path}")

    return json.dumps(
        {
            "path": path,
            "fromCache": False,
            "cacheAgeSec": 0,
            "liveStatus": final_info.get("live_status"),
        }
    )
