import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";

import { describe, expect, it } from "vitest";

const APP = join(__dirname);

function pages(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return pages(path);
    }
    return name === "page.tsx" ? [path] : [];
  });
}

/** 경로의 제목: 페이지가 직접 내보내거나, 클라이언트 페이지면 같은 폴더의 layout이 내보낸다. */
function titleOf(page: string): string | null {
  const layout = join(dirname(page), "layout.tsx");
  for (const file of [page, layout]) {
    if (!existsSync(file)) {
      continue;
    }
    const match = /export const metadata[^=]*=\s*{\s*title:\s*"([^"]+)"/.exec(
      readFileSync(file, "utf8"),
    );
    if (match) {
      return match[1];
    }
  }
  return null;
}

describe("경로마다의 화면 제목", () => {
  it("DS-US4-AC1 루트는 경로의 제목 뒤에 서비스 이름을 붙인다", () => {
    const root = readFileSync(join(APP, "layout.tsx"), "utf8");
    expect(root).toContain(
      'title: { default: "오구오구", template: "%s | 오구오구" }',
    );
  });

  // 첫 화면(/)은 서비스 이름 그대로 쓰고, 디버그 화면은 운영에서 열리지 않는다.
  const routes = pages(APP)
    .map((page) => relative(APP, page))
    .filter((page) => page !== "page.tsx" && !page.startsWith("debug/"));

  it.each(routes)("DS-US4-AC1 %s 는 제 이름의 제목을 가진다", (route) => {
    const title = titleOf(join(APP, route));
    expect(title).not.toBeNull();
    expect(title).not.toBe("오구오구");
  });

  it("DS-US4-AC1 제목은 서로 겹치지 않는다", () => {
    const titles = routes.map((route) => titleOf(join(APP, route)));
    expect(new Set(titles).size).toBe(titles.length);
  });
});
