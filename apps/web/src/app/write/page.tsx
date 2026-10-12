import { WriteForm } from "@/features/write-post";
import { Card } from "@/shared/ui";

/** 고민 글쓰기(US1). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다(US1-AC7). */
export default function WritePage() {
  return (
    <Card className="w-full max-w-xl p-6 sm:p-8">
      <span className="bg-brand-50 text-brand-700 mb-4 inline-flex h-12 w-12 items-center justify-center rounded-2xl">
        <PencilLine size={24} aria-hidden />
      </span>
      <h1 className="text-2xl font-bold text-neutral-900">고민 쓰기</h1>
      <p className="mt-2 text-sm leading-6 text-neutral-500">
        털어놓으면 고민이 몬스터가 돼요. 다른 사람들의 공감과 댓글로 함께
        물리쳐요.
      </p>
      <div className="mt-6">
        <WriteForm />
      </div>
    </Card>
  );
}
import { PencilLine } from "lucide-react";
