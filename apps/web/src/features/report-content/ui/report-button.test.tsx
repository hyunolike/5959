import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ReportButton } from "./report-button";

function stub(status: number, code?: string) {
  const fetchMock = vi.fn().mockImplementation(async () =>
    status === 204
      ? new Response(null, { status: 204 })
      : new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: { code, message: "서버 문구" },
          }),
          { status, headers: { "content-type": "application/json" } },
        ),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderButton(targetType: "POST" | "COMMENT" = "POST") {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ReportButton targetType={targetType} targetId={7} />
    </QueryClientProvider>,
  );
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "신고" }));
  return screen.getByRole("alertdialog");
}

const sentBody = (fetchMock: ReturnType<typeof stub>) =>
  JSON.parse(String(fetchMock.mock.calls[0][1]?.body));

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("ReportButton", () => {
  it("US3-AC1 사유 넷을 보이고, 골라서 신고하면 접수 안내로 바뀐다", async () => {
    const user = userEvent.setup();
    const fetchMock = stub(204);
    renderButton();

    const dialog = await openDialog(user);
    expect(dialog).toHaveTextContent("이 글을 신고할까요?");
    expect(dialog).toHaveTextContent(
      "신고한 사람은 글쓴이에게 알려지지 않아요.",
    );
    expect(
      within(dialog)
        .getAllByRole("radio")
        .map((radio) => radio.parentElement?.textContent),
    ).toEqual([
      "위험해 보여요",
      "욕설이나 비방이에요",
      "광고나 도배예요",
      "기타",
    ]);

    await user.click(
      within(dialog).getByRole("radio", { name: "욕설이나 비방이에요" }),
    );
    await user.click(within(dialog).getByRole("button", { name: "신고하기" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "신고가 접수됐어요",
    );
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "신고" })).toBeNull();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/reports",
      expect.objectContaining({ method: "POST" }),
    );
    expect(sentBody(fetchMock)).toEqual({
      targetType: "POST",
      targetId: 7,
      reason: "ABUSIVE",
    });
  });

  it("US3-AC1 기타를 고르면 설명을 적을 수 있고 글자 수를 세며, 설명을 함께 보낸다", async () => {
    const user = userEvent.setup();
    const fetchMock = stub(204);
    renderButton("COMMENT");
    const dialog = await openDialog(user);
    expect(dialog).toHaveTextContent("이 댓글을 신고할까요?");
    expect(within(dialog).queryByRole("textbox")).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole("radio", { name: "기타" }));
    await user.type(
      within(dialog).getByRole("textbox"),
      "  다른 사람의 글을 그대로 옮겼어요  ",
    );

    expect(dialog).toHaveTextContent("18/200");
    await user.click(within(dialog).getByRole("button", { name: "신고하기" }));

    await screen.findByRole("status");
    expect(sentBody(fetchMock)).toEqual({
      targetType: "COMMENT",
      targetId: 7,
      reason: "OTHER",
      detail: "다른 사람의 글을 그대로 옮겼어요",
    });
  });

  it("기타의 설명이 200자를 넘으면 보내지 않고 안내한다", async () => {
    const user = userEvent.setup();
    const fetchMock = stub(204);
    renderButton();
    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("radio", { name: "기타" }));
    await user.click(within(dialog).getByRole("textbox"));
    await user.paste("가".repeat(201));

    await user.click(within(dialog).getByRole("button", { name: "신고하기" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "설명은 200자까지 쓸 수 있어요.",
    );
    expect(dialog).toHaveTextContent("201/200");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("사유를 고르지 않으면 보내지 않고 안내한다", async () => {
    const user = userEvent.setup();
    const fetchMock = stub(204);
    renderButton();
    const dialog = await openDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "신고하기" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "신고 사유를 골라 주세요.",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('US3-AC2 409면 대화상자를 닫고 "이미 신고한 글이에요"를 보인다', async () => {
    const user = userEvent.setup();
    stub(409, "ALREADY_REPORTED");
    renderButton();
    const dialog = await openDialog(user);
    await user.click(
      within(dialog).getByRole("radio", { name: "광고나 도배예요" }),
    );

    await user.click(within(dialog).getByRole("button", { name: "신고하기" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "이미 신고한 글이에요",
    );
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("US3-AC5 429면 대화상자 안에 잠시 뒤 다시 시도하라고 안내한다", async () => {
    const user = userEvent.setup();
    stub(429, "REPORT_RATE_LIMITED");
    renderButton();
    const dialog = await openDialog(user);
    await user.click(
      within(dialog).getByRole("radio", { name: "위험해 보여요" }),
    );

    await user.click(within(dialog).getByRole("button", { name: "신고하기" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "신고를 너무 자주 보내고 있어요.",
    );
    expect(screen.getByRole("alertdialog")).toBeVisible();
  });

  it("취소하면 고른 것과 적은 것을 비우고 닫는다", async () => {
    const user = userEvent.setup();
    const fetchMock = stub(204);
    renderButton();
    let dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("radio", { name: "기타" }));
    await user.type(within(dialog).getByRole("textbox"), "적다 만 설명");

    await user.click(within(dialog).getByRole("button", { name: "취소" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    dialog = await openDialog(user);
    expect(
      within(dialog).getByRole("radio", { name: "기타" }),
    ).not.toBeChecked();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
