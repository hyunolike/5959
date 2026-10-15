import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

const post = vi.hoisted(() => ({ hasMyPost: false as boolean | undefined }));
vi.mock("@/entities/post", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/entities/post")>()),
  useHasMyPostQuery: () => post.hasMyPost,
}));

import { FirstPostPrompt } from "./first-post-prompt";

const WELCOME = "ogu.welcome.pending";
const DISMISSED = "ogu.firstPostPrompt.dismissed";

afterEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.restoreAllMocks();
  post.hasMyPost = false;
});

describe("FirstPostPrompt 환영 안내", () => {
  it("US2-AC2 온보딩 직후면 환영 안내와 두 길이 보이고 첫 고민 카드는 없다", async () => {
    sessionStorage.setItem(WELCOME, "1");
    render(<FirstPostPrompt />);

    expect(
      await screen.findByRole("link", { name: "털어놓기" }),
    ).toHaveAttribute("href", "/write");
    expect(
      screen.getByRole("button", { name: "먼저 둘러볼게요" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "첫 고민 남기기" }),
    ).not.toBeInTheDocument();
  });

  it("US2-AC3 먼저 둘러볼게요를 누르면 안내가 닫힌다", async () => {
    const user = userEvent.setup({ delay: null });
    sessionStorage.setItem(WELCOME, "1");
    render(<FirstPostPrompt />);

    await user.click(
      await screen.findByRole("button", { name: "먼저 둘러볼게요" }),
    );

    expect(
      screen.queryByRole("link", { name: "털어놓기" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "첫 고민 남기기" }),
    ).not.toBeInTheDocument();
  });

  it("US2-AC5 한 번 보이면 표시가 지워져 다시 그리면 환영 안내가 없다", async () => {
    sessionStorage.setItem(WELCOME, "1");
    const { unmount } = render(<FirstPostPrompt />);
    await screen.findByRole("link", { name: "털어놓기" });
    expect(sessionStorage.getItem(WELCOME)).toBeNull();
    unmount();

    render(<FirstPostPrompt />);
    expect(
      await screen.findByRole("link", { name: "첫 고민 남기기" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "털어놓기" }),
    ).not.toBeInTheDocument();
  });

  it("StrictMode로 두 번 그려도 환영 안내가 보인다", async () => {
    sessionStorage.setItem(WELCOME, "1");
    render(
      <StrictMode>
        <FirstPostPrompt />
      </StrictMode>,
    );

    expect(
      await screen.findByRole("link", { name: "털어놓기" }),
    ).toBeInTheDocument();
  });
});

describe("FirstPostPrompt 첫 고민 카드", () => {
  it("US2-AC6 내 글이 없으면 첫 고민 카드와 쓰기로 가는 길이 보인다", async () => {
    render(<FirstPostPrompt />);

    expect(
      await screen.findByRole("link", { name: "첫 고민 남기기" }),
    ).toHaveAttribute("href", "/write");
    expect(screen.getByText(/몬스터가 나타나요/)).toBeInTheDocument();
  });

  it("US2-AC7 내 글이 있으면 아무것도 없다", () => {
    post.hasMyPost = true;
    const { container } = render(<FirstPostPrompt />);
    expect(container).toBeEmptyDOMElement();
  });

  it("US2-AC9 내 글이 있는지 모르면 아무것도 없다", () => {
    post.hasMyPost = undefined;
    const { container } = render(<FirstPostPrompt />);
    expect(container).toBeEmptyDOMElement();
  });

  it("US2-AC8 닫으면 사라지고 같은 브라우저에서 다시 그려도 없다", async () => {
    const user = userEvent.setup({ delay: null });
    const { unmount } = render(<FirstPostPrompt />);

    await user.click(
      await screen.findByRole("button", { name: "첫 고민 안내 닫기" }),
    );
    expect(
      screen.queryByRole("link", { name: "첫 고민 남기기" }),
    ).not.toBeInTheDocument();
    expect(localStorage.getItem(DISMISSED)).toBe("1");
    unmount();

    const { container } = render(<FirstPostPrompt />);
    expect(container).toBeEmptyDOMElement();
  });

  it("저장소가 막혀도 닫기는 이번 화면에서 된다", async () => {
    const user = userEvent.setup({ delay: null });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    render(<FirstPostPrompt />);

    await user.click(
      await screen.findByRole("button", { name: "첫 고민 안내 닫기" }),
    );
    expect(
      screen.queryByRole("link", { name: "첫 고민 남기기" }),
    ).not.toBeInTheDocument();
  });
});
