"""Regenerate bundled character references and run one story-image smoke test.

The script intentionally mirrors the ModelScope async API used by the Java
service. It never prints credentials and only replaces an existing asset after
the downloaded bytes have been validated as a readable PNG.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import time
from io import BytesIO
from pathlib import Path

import requests
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
PRESET_DIR = ROOT / "src" / "main" / "resources" / "static" / "files" / "presets"

PRESETS = [
    ("bear-nuomi.png", "cute chubby cream bear, round ears, red scarf, cartoon full body"),
    ("rabbit-lulu.png", "cute white rabbit girl, pink ears, yellow dress, cartoon full body"),
    ("fox-orange.png", "cute orange fox child, white chest, blue backpack, cartoon full body"),
    ("dragon-aguang.png", "tiny friendly mint dragon, star-shaped freckles, purple wings, cartoon full body"),
    ("unicorn-rainbow.png", "cute pastel unicorn, rainbow mane, golden horn, cartoon full body"),
    ("boy-lele.png", "six year old Chinese boy, short black hair, yellow hoodie, blue shorts, cartoon full body"),
    ("girl-duoduo.png", "seven year old girl, bob haircut, pink dress, yellow rain boots, cartoon full body"),
    ("robot-77.png", "small rounded helper robot, sky blue body, heart display, cartoon full body"),
    ("penguin-bing.png", "cute penguin child, navy body, orange scarf, cartoon full body"),
    ("pirate-doudou.png", "cute child pirate captain, red hat, striped shirt, cartoon full body"),
    ("elf-aiye.png", "cute forest elf child, green hood, leaf staff, cartoon full body"),
    ("girl-anan.png", "five year old girl, two pigtails, mint overalls, orange shoes, cartoon full body"),
]


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^\s*([A-Z0-9_]+)=(.*)\s*$", line)
            if match:
                values[match.group(1)] = match.group(2).strip().strip('"')
    values.update({key: value for key, value in os.environ.items() if key in {
        "IMAGE_API_KEY", "IMAGE_BASE_URL", "IMAGE_MODEL"
    }})
    return values


def model_scope_image(prompt: str, values: dict[str, str], reference_paths: list[Path] | None = None) -> bytes:
    base_url = values.get("IMAGE_BASE_URL", "https://api-inference.modelscope.cn/").rstrip("/")
    api_key = values.get("IMAGE_API_KEY", "")
    model = values.get("IMAGE_MODEL", "Qwen/Qwen-Image-2.1")
    if not api_key:
        raise RuntimeError("IMAGE_API_KEY is missing")
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    payload = {"model": model, "prompt": prompt}
    if reference_paths:
        payload["image_url"] = [
            "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
            for path in reference_paths
        ]
    response = requests.post(
        f"{base_url}/v1/images/generations", headers={**headers, "X-ModelScope-Async-Mode": "true"},
        data=json.dumps(payload, ensure_ascii=False), timeout=60,
    )
    response.raise_for_status()
    task_id = response.json().get("task_id")
    if not task_id:
        raise RuntimeError("ModelScope did not return task_id")
    for _ in range(60):
        result = requests.get(
            f"{base_url}/v1/tasks/{task_id}",
            headers={**headers, "X-ModelScope-Task-Type": "image_generation"}, timeout=60,
        )
        result.raise_for_status()
        data = result.json()
        status = str(data.get("task_status", "")).upper()
        if status == "SUCCEED":
            urls = data.get("output_images") or []
            if not urls:
                raise RuntimeError("ModelScope returned no output_images")
            image = requests.get(urls[0], timeout=120)
            image.raise_for_status()
            return image.content
        if status == "FAILED":
            raise RuntimeError(f"ModelScope task failed: {data.get('message') or data.get('error') or 'unknown error'}")
        time.sleep(5)
    raise TimeoutError("ModelScope task timed out after 5 minutes")


def validate_png(data: bytes) -> tuple[int, int]:
    with Image.open(BytesIO(data)) as image:
        image.verify()
    with Image.open(BytesIO(data)) as image:
        return image.size


def character_prompt(description: str, values: dict[str, str]) -> str:
    deepseek_key = values.get("AI_API_KEY", "")
    if not deepseek_key:
        return (
            "Production reference sheet for a children's picture book character. "
            f"Character design: {description}. Full body, neutral standing pose, clear silhouette, "
            "front view plus small side and back views, consistent colors and clothing, plain light "
            "background, friendly polished cartoon illustration, no text, no letters, no logo, no watermark."
        )
    deepseek_base = values.get("AI_BASE_URL", "https://api.deepseek.com").rstrip("/")
    deepseek_model = values.get("AI_CHAT_MODEL", "deepseek-chat")
    response = requests.post(
        f"{deepseek_base}/v1/chat/completions",
        headers={"Authorization": f"Bearer {deepseek_key}", "Content-Type": "application/json"},
        json={"model": deepseek_model, "temperature": 0.2, "max_tokens": 500, "messages": [{
            "role": "user",
            "content": (
                "Write one precise English image prompt for a production character reference sheet "
                "for a children's picture book. Preserve this character's species, age, face, colors, "
                "clothing, accessories and distinctive features. Show one full-body neutral standing "
                "front view with small side and back views, clear silhouette, plain light background. "
                "Friendly polished cartoon art. No story scene, extra people, text, letters, logos or "
                "watermark. Return only the prompt. Character design: " + description
            ),
        }]},
        timeout=90,
    )
    response.raise_for_status()
    content = response.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
    if not content:
        raise RuntimeError("DeepSeek returned an empty character image prompt")
    return content


def story_prompt(values: dict[str, str]) -> str:
    story = "小狐狸橘子在森林里追着一只蓝色风筝，风筝挂在苹果树上；小熊糯米和橘子一起把风筝取下来。"
    deepseek_key = values.get("AI_API_KEY", "")
    deepseek_base = values.get("AI_BASE_URL", "https://api.deepseek.com").rstrip("/")
    deepseek_model = values.get("AI_CHAT_MODEL", "deepseek-chat")
    if not deepseek_key:
        return (
            "Children's picture-book illustration: in a sunny green forest, a cute orange fox child "
            "with a white chest and blue backpack runs after a bright blue kite caught in an apple tree; "
            "a chubby cream bear wearing a red scarf helps the fox gently retrieve the kite. Show the exact "
            "action, apple tree, blue kite and both friends clearly in one full-bleed scene. The supplied "
            "images are character identity references only; do not copy their reference-sheet layout or poses. "
            "Do not show a physical picture book, page, cover, mockup, frame or panel. No visible text, letters, "
            "numbers, signs, captions, speech bubbles, logos or watermark. Warm cartoon style."
        )
    response = requests.post(
        f"{deepseek_base}/v1/chat/completions",
        headers={"Authorization": f"Bearer {deepseek_key}", "Content-Type": "application/json"},
        json={"model": deepseek_model, "temperature": 0.2, "max_tokens": 500, "messages": [{
            "role": "user",
            "content": (
                "Write one English image prompt for this children's story. Depict the exact moment the "
                "friends are retrieving the kite: the fox reaches up and the bear helps lower the blue "
                "kite from an apple-tree branch. The kite is already detached from the branch and is "
                "clearly being lowered toward the fox, held by the bear; do not leave it snagged in the tree. "
                "Preserve every concrete object, place and named character; do not invent "
                "unrelated events. Make one full-bleed scene. Use attached images only for character identity; "
                "do not copy their turnaround layout or poses. Never depict a physical picture book, printed "
                "page, cover, mockup, frame or panel. No visible writing of any kind, letters, numbers, signs, "
                "captions, speech bubbles, logos or watermark. "
                "Return only the prompt. Story: " + story
            ),
        }]},
        timeout=90,
    )
    response.raise_for_status()
    content = response.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
    return content or story_prompt({"IMAGE_API_KEY": ""})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-presets", action="store_true")
    parser.add_argument("--story-test", action="store_true")
    args = parser.parse_args()
    values = load_env()
    PRESET_DIR.mkdir(parents=True, exist_ok=True)

    if not args.skip_presets:
        for filename, description in PRESETS:
            target = PRESET_DIR / filename
            data = model_scope_image(character_prompt(description, values), values)
            size = validate_png(data)
            temporary = target.with_suffix(target.suffix + ".new")
            temporary.write_bytes(data)
            os.replace(temporary, target)
            print(f"regenerated {filename} ({size[0]}x{size[1]}, {len(data)} bytes)")

    if args.story_test:
        prompt = story_prompt(values)
        references = [PRESET_DIR / "fox-orange.png", PRESET_DIR / "bear-nuomi.png"]
        data = model_scope_image(prompt, values, references)
        size = validate_png(data)
        output = ROOT / "uploads" / "modelscope-story-relevance-test.png"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        print(f"story_test {output} ({size[0]}x{size[1]}, {len(data)} bytes)")
        print("story_prompt=" + prompt)


if __name__ == "__main__":
    main()
