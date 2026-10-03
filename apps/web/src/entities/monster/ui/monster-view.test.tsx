import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { MonsterView } from "../model/types";

/** 가짜 3D 장면이 렌더링 중에 던질지(WebGLRenderer 생성 실패). */
const scene3d = { throws: false };

/** 3D 장면(three, R3F)은 jsdom에서 그릴 수 없다. 고른 결과만 보이게 바꿔 둔다. */
function fakeScene() {
  return {
    default: ({ label }: { label: string }) => {
      if (scene3d.throws) {
        throw new Error("Error creating WebGL context.");
      }
      return <div data-testid="monster-3d" role="img" aria-label={label} />;
    },
  };
}

const ANXIETY_FULL: MonsterView = {
  emotion: "ANXIETY",
  hp: 10,
  maxHp: 10,
  status: "ALIVE",
};

/** 움직임 줄이기 설정을 흉내 낸다. 돌려준 함수로 설정을 바꾸고 change 이벤트를 보낸다. */
function stubReducedMotion(reduce: boolean): (next: boolean) => void {
  let current = reduce;
  const listeners = new Set<() => void>();
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      get matches() {
        return current && query === "(prefers-reduced-motion: reduce)";
      },
      media: query,
      addEventListener: (_type: string, listener: () => void) =>
        listeners.add(listener),
      removeEventListener: (_type: string, listener: () => void) =>
        listeners.delete(listener),
    })),
  );
  return (next) => {
    current = next;
    listeners.forEach((listener) => listener());
  };
}

/** WebGL 컨텍스트를 만들 수 있는 기기인지 흉내 낸다. */
function stubWebGL(supported: boolean) {
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation((() =>
    supported
      ? { getExtension: () => ({ loseContext: () => {} }) }
      : null) as unknown as HTMLCanvasElement["getContext"]);
}

/** WebGL 지원 여부를 모듈이 한 번만 확인해 기억하므로 테스트마다 새로 불러온다. */
async function loadDisplay() {
  vi.resetModules();
  const mod = await import("./monster-view");
  return mod.MonsterDisplay;
}

function spriteSrc(img: HTMLElement): string {
  return decodeURIComponent(img.getAttribute("src") ?? "");
}

let animate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  scene3d.throws = false;
  vi.doMock("./monster-3d", fakeScene);
  animate = vi.fn();
  // jsdom에는 Web Animations API가 없다.
  Object.defineProperty(HTMLElement.prototype, "animate", {
    configurable: true,
    value: animate,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete (HTMLElement.prototype as Partial<HTMLElement>).animate;
});

describe("MonsterDisplay 3D와 정지 이미지 고르기", () => {
  it("US5-AC4 WebGL 미지원이나 움직임 줄이기면 정지 이미지를 렌더링한다", async () => {
    // WebGL 미지원
    stubReducedMotion(false);
    stubWebGL(false);
    let MonsterDisplay = await loadDisplay();
    const first = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    const noWebgl = screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" });
    expect(noWebgl.tagName).toBe("IMG");
    expect(spriteSrc(noWebgl)).toContain("/monsters/anxiety-full.png");
    expect(screen.queryByTestId("monster-3d")).not.toBeInTheDocument();
    // 기능(HP 표시)은 그대로다
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "10");
    first.unmount();
    vi.restoreAllMocks();

    // 움직임 줄이기
    stubReducedMotion(true);
    stubWebGL(true);
    MonsterDisplay = await loadDisplay();
    render(<MonsterDisplay monster={ANXIETY_FULL} variant="detail" />);
    const reduced = screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" });
    expect(reduced.tagName).toBe("IMG");
    expect(screen.queryByTestId("monster-3d")).not.toBeInTheDocument();
  });

  it("WebGL을 쓸 수 있고 움직임 줄이기가 꺼져 있으면 상세에서 3D 장면을 불러온다", async () => {
    stubReducedMotion(false);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    render(<MonsterDisplay monster={ANXIETY_FULL} variant="detail" />);

    // 3D를 불러오는 동안에는 빈 자리 대신 정지 이미지를 보여 준다
    const loading = screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" });
    expect(loading.tagName).toBe("IMG");

    const scene = await screen.findByTestId("monster-3d");
    expect(scene).toHaveAccessibleName("불안 몬스터, 멀쩡함");
    expect(document.querySelector("img")).toBeNull();
  });

  it("US5-AC4 상세를 보는 중에 움직임 줄이기를 켜면 3D에서 정지 이미지로 바꾼다", async () => {
    const setReduced = stubReducedMotion(false);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    render(<MonsterDisplay monster={ANXIETY_FULL} variant="detail" />);
    await screen.findByTestId("monster-3d");

    act(() => setReduced(true));

    expect(screen.queryByTestId("monster-3d")).not.toBeInTheDocument();
    const sprite = screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" });
    expect(sprite.tagName).toBe("IMG");
  });

  it("US5-AC4 3D 장면이 렌더링 중에 실패하면(WebGL 컨텍스트 생성 실패) 정지 이미지로 대신하고 HP와 맞는 반응은 그대로다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    scene3d.throws = true;
    stubReducedMotion(false);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );

    await vi.waitFor(() =>
      expect(
        screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" }).tagName,
      ).toBe("IMG"),
    );
    // 잠깐 불러오는 중 이미지가 아니라 실패 뒤에도 남는 정지 이미지다
    await new Promise((resolve) => setTimeout(resolve, 20));
    const sprite = screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" });
    expect(sprite.tagName).toBe("IMG");
    expect(screen.getByText("불안", { exact: true })).toBeInTheDocument();

    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );
    expect(screen.getByText("HP 9/10")).toBeInTheDocument();
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "9");
    expect(animate).toHaveBeenCalledTimes(1);
    // 정지 이미지로 바뀌었으니 그림과 HP 바를 함께 흔든다
    const shaken = animate.mock.contexts[0] as HTMLElement;
    expect(shaken).toContainElement(screen.getByRole("img"));
    expect(shaken).toContainElement(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    );
  });

  it("US5-AC4 3D 모듈을 불러오지 못하면(청크 로딩 실패) 정지 이미지로 대신한다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    // 모듈 불러오기가 실패한다(배포 뒤 청크가 사라졌거나 네트워크 오류)
    vi.doMock("./monster-3d", () => {
      throw new Error("ChunkLoadError: 3D 청크를 불러오지 못했다");
    });
    stubReducedMotion(false);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    render(<MonsterDisplay monster={ANXIETY_FULL} variant="detail" />);

    await new Promise((resolve) => setTimeout(resolve, 20));
    const sprite = screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" });
    expect(sprite.tagName).toBe("IMG");
    expect(screen.queryByTestId("monster-3d")).not.toBeInTheDocument();
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "10");
  });

  it("US5-AC3 피드 카드는 항상 정지 이미지이고 감정과 단계에 맞는 파일을 쓴다", async () => {
    // 3D를 쓸 수 있는 기기여도 피드는 정지 이미지다
    stubReducedMotion(false);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    const cases: [MonsterView, string, string][] = [
      [ANXIETY_FULL, "/monsters/anxiety-full.png", "불안 몬스터, 멀쩡함"],
      [
        { emotion: "LETHARGY", hp: 13, maxHp: 20, status: "ALIVE" },
        "/monsters/lethargy-hurt.png",
        "무기력 몬스터, 상처 입음",
      ],
      [
        { emotion: "LONELINESS", hp: 20, maxHp: 30, status: "ALIVE" },
        "/monsters/loneliness-hurt.png",
        "외로움 몬스터, 상처 입음",
      ],
      [
        { emotion: "SELF_DEPRECATION", hp: 10, maxHp: 30, status: "ALIVE" },
        "/monsters/self_deprecation-weak.png",
        "자기비하 몬스터, 약해짐",
      ],
      [
        { emotion: "IRRITATION", hp: 0, maxHp: 10, status: "DEFEATED" },
        "/monsters/irritation-defeated.png",
        "짜증 몬스터, 쓰러짐",
      ],
    ];

    for (const [monster, file, name] of cases) {
      const view = render(<MonsterDisplay monster={monster} variant="card" />);
      const img = screen.getByRole("img", { name });
      expect(img.tagName).toBe("IMG");
      expect(spriteSrc(img)).toContain(file);
      view.unmount();
    }
    // 피드는 3D 모듈을 부르지 않는다
    await Promise.resolve();
    expect(screen.queryByTestId("monster-3d")).not.toBeInTheDocument();
  });
});

describe("MonsterDisplay 감정 이름과 HP", () => {
  it("감정 이름, HP 숫자, HP 바를 보여 준다", async () => {
    stubReducedMotion(false);
    stubWebGL(false);
    const MonsterDisplay = await loadDisplay();
    render(
      <MonsterDisplay
        monster={{ emotion: "IRRITATION", hp: 4, maxHp: 10, status: "ALIVE" }}
        variant="card"
      />,
    );
    expect(screen.getByText("짜증", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("HP 4/10")).toBeInTheDocument();
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "4");
  });

  it("US3-AC5 처치된 몬스터는 처치됨을 보여 준다", async () => {
    stubReducedMotion(false);
    stubWebGL(false);
    const MonsterDisplay = await loadDisplay();
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    expect(screen.queryByText("처치됨")).not.toBeInTheDocument();

    rerender(
      <MonsterDisplay
        monster={{ ...ANXIETY_FULL, hp: 0, status: "DEFEATED" }}
        variant="detail"
      />,
    );
    expect(screen.getByText("처치됨")).toBeInTheDocument();
    expect(screen.getByText("HP 0/10")).toBeInTheDocument();
    expect(
      spriteSrc(screen.getByRole("img", { name: "불안 몬스터, 쓰러짐" })),
    ).toContain("/monsters/anxiety-defeated.png");
  });
});

describe("MonsterDisplay 맞는 반응", () => {
  it("US3-AC10 HP가 줄면 정지 이미지와 HP 바를 함께 흔들고 바뀐 HP를 바로 보여 준다", async () => {
    stubReducedMotion(false);
    stubWebGL(false);
    const { HIT_REACTION_MS } = await import("./use-hit-reaction");
    const MonsterDisplay = await loadDisplay();
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    expect(animate).not.toHaveBeenCalled();

    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );

    expect(screen.getByText("HP 9/10")).toBeInTheDocument();
    expect(animate).toHaveBeenCalledTimes(1);
    expect(animate.mock.calls[0][1]).toMatchObject({
      duration: HIT_REACTION_MS,
    });
    const shaken = animate.mock.contexts[0] as HTMLElement;
    expect(shaken).toContainElement(screen.getByRole("img"));
    expect(shaken).toContainElement(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    );
  });

  it("US3-AC10 3D일 때는 장면이 맞는 반응을 하고 HP 바만 흔든다", async () => {
    stubReducedMotion(false);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    const scene = await screen.findByTestId("monster-3d");

    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );

    expect(animate).toHaveBeenCalledTimes(1);
    const shaken = animate.mock.contexts[0] as HTMLElement;
    expect(shaken).toContainElement(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    );
    expect(shaken).not.toContainElement(scene);
  });

  it("HP가 늘거나 그대로면(서버 값으로 맞출 때) 흔들지 않는다", async () => {
    stubReducedMotion(false);
    stubWebGL(false);
    const MonsterDisplay = await loadDisplay();
    const { rerender } = render(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 7 }} variant="detail" />,
    );
    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );
    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );
    expect(animate).not.toHaveBeenCalled();
  });

  it("움직임 줄이기가 켜져 있으면 흔들지 않고 HP만 바꾼다", async () => {
    stubReducedMotion(true);
    stubWebGL(true);
    const MonsterDisplay = await loadDisplay();
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );

    expect(screen.getByText("HP 9/10")).toBeInTheDocument();
    expect(animate).not.toHaveBeenCalled();
  });
});
