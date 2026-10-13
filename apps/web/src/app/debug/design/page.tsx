import {
  Bell,
  ChatCircle,
  Heart,
  House,
  PencilSimpleLine,
  Sword,
  User,
} from "@phosphor-icons/react/dist/ssr";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";

import {
  EMOTION_LABELS,
  EmotionTag,
  HpBar,
  MonsterSprite,
  MonsterStage,
  MonsterThumb,
  type EmotionType,
} from "@/entities/monster";
import { env } from "@/shared/config";
import { formatRelativeTime } from "@/shared/lib";
import {
  Button,
  Card,
  Chip,
  GameButton,
  IconButton,
  ListSkeleton,
  Select,
  Spinner,
  TextField,
  Textarea,
} from "@/shared/ui";

import { CatalogOverlays } from "./catalog-overlays";

const EMOTIONS = Object.keys(EMOTION_LABELS) as EmotionType[];

const ROLE_COLORS: [name: string, className: string][] = [
  ["page", "bg-page"],
  ["surface", "bg-surface"],
  ["surface-sunken", "bg-surface-sunken"],
  ["line", "bg-line"],
  ["line-strong", "bg-line-strong"],
  ["fg-subtle", "bg-fg-subtle"],
  ["fg-muted", "bg-fg-muted"],
  ["fg / action", "bg-fg"],
  ["danger", "bg-danger"],
  ["danger-tint", "bg-danger-tint"],
  ["warning", "bg-warning"],
  ["warning-tint", "bg-warning-tint"],
  ["game", "bg-game"],
];

const TYPE_SCALE: [name: string, className: string, sample: string][] = [
  ["numeric 36/40", "text-numeric font-extrabold tabular-nums", "4,120"],
  ["display 32/40", "text-display font-extrabold", "짜증"],
  ["heading 24/32", "text-heading font-bold", "마음 이야기"],
  ["title 20/28", "text-title font-bold", "댓글 7"],
  ["body-lg 17/29", "text-body-lg", "내일 배포인데 테스트를 다 못 돌렸다."],
  ["body 16/26", "text-body", "내일 배포인데 테스트를 다 못 돌렸다."],
  ["body-sm 15/24", "text-body-sm", "저도 배포 전날엔 늘 그래요."],
  ["label 13/18", "text-label text-fg-subtle", "개발 3년차"],
  ["caption 12/16", "text-caption font-bold", "HP 62"],
];

const NOW = "2026-10-13T12:00:00+09:00";
const TIMES = [
  "2026-10-13T11:59:40+09:00",
  "2026-10-13T11:48:00+09:00",
  "2026-10-13T09:00:00+09:00",
  "2026-10-10T12:00:00+09:00",
  "2026-09-30T12:00:00+09:00",
];

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-line flex flex-col gap-4 border-t py-6">
      <h2 className="text-title font-bold">{title}</h2>
      {children}
    </section>
  );
}

/**
 * 디자인 토큰과 공용 컴포넌트를 한 화면에서 보는 디버그 전용 페이지(DS-US2-AC4).
 * `ENABLE_DESIGN_CATALOG=1`일 때만 있고 그 밖에는 404다. 보호 경로가 아니라 로그인 없이 열린다.
 */
export default function DesignCatalogPage() {
  if (env.ENABLE_DESIGN_CATALOG !== true) {
    notFound();
  }

  return (
    <div className="text-fg flex w-full max-w-[720px] flex-col px-5 pb-16">
      <h1 className="text-heading py-6 font-bold">디자인 카탈로그</h1>

      <Section title="역할 색">
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {ROLE_COLORS.map(([name, className]) => (
            <li key={name} className="flex flex-col gap-1">
              <span
                className={`rounded-control border-line h-12 border ${className}`}
              />
              <span className="text-caption text-fg-muted">{name}</span>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="글자 단계">
        <ul className="flex flex-col gap-4">
          {TYPE_SCALE.map(([name, className, sample]) => (
            <li key={name} className="flex flex-col gap-1">
              <span className="text-caption text-fg-subtle">{name}</span>
              <span className={className}>{sample}</span>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="버튼">
        <div className="flex flex-wrap gap-2">
          <Button>주요 동작</Button>
          <Button variant="secondary">테두리</Button>
          <Button variant="subtle">가라앉은 면</Button>
          <Button variant="ghost">글자만</Button>
          <Button variant="danger">삭제</Button>
          <Button disabled>잠김</Button>
          <Button size="sm" variant="secondary">
            작은 버튼
          </Button>
        </div>
        <Button size="lg">
          로그인하고 다른 회원들의 마음 이야기를 처음부터 끝까지 읽어 보기
        </Button>
        <div className="flex gap-1">
          <IconButton aria-label="알림">
            <Bell size={24} aria-hidden />
          </IconButton>
          <IconButton aria-label="공감">
            <Heart size={24} weight="fill" aria-hidden />
          </IconButton>
          <IconButton aria-label="댓글">
            <ChatCircle size={24} aria-hidden />
          </IconButton>
        </div>
      </Section>

      <Section title="게임 버튼">
        <GameButton>공격하기</GameButton>
        <GameButton>
          <Heart size={24} weight="fill" aria-hidden />
          공감 보내기
        </GameButton>
        <GameButton aria-pressed>공감 4</GameButton>
        <GameButton disabled aria-busy>
          공감 5
        </GameButton>
        <GameButton disabled>공격하기</GameButton>
      </Section>

      <Section title="칩">
        <div className="flex flex-wrap gap-2">
          <Chip pressed>전체</Chip>
          <Chip pressed={false}>개발</Chip>
          <Chip pressed={false}>디자인</Chip>
          <Chip pressed={false}>1~3년차</Chip>
        </div>
      </Section>

      <Section title="입력">
        <TextField label="닉네임" hint="2~10자" placeholder="조용한수달" />
        <TextField
          label="이메일"
          defaultValue="ogu@"
          error="이메일 형식이 아니에요"
        />
        <TextField label="잠긴 입력" defaultValue="바꿀 수 없어요" disabled />
        <label className="flex flex-col gap-2">
          <span className="text-label text-fg-muted font-medium">직군</span>
          <Select defaultValue="">
            <option value="" disabled>
              직군을 선택하세요
            </option>
            <option value="DEVELOPMENT">개발</option>
            <option value="DESIGN">디자인</option>
          </Select>
        </label>
        <label className="flex flex-col gap-2">
          <span className="text-label text-fg-muted font-medium">
            잠긴 선택
          </span>
          <Select defaultValue="DEVELOPMENT" disabled>
            <option value="DEVELOPMENT">개발</option>
          </Select>
        </label>
        <label className="flex flex-col gap-2">
          <span className="text-label text-fg-muted font-medium">
            잘못된 선택
          </span>
          <Select defaultValue="" aria-invalid>
            <option value="" disabled>
              경력을 선택하세요
            </option>
          </Select>
        </label>
        <label className="flex flex-col gap-2">
          <span className="text-label text-fg-muted font-medium">고민</span>
          <Textarea placeholder="요즘 마음을 무겁게 하는 일을 적어 주세요" />
        </label>
      </Section>

      <Section title="면과 불러오는 중">
        <Card data-testid="catalog-card">
          <p className="text-body">테두리로 구분하는 면이에요.</p>
        </Card>
        <div data-testid="catalog-loading" className="flex flex-col gap-3">
          <Spinner />
          <ListSkeleton label="글" />
        </div>
      </Section>

      <Section title="대화상자와 토스트">
        <CatalogOverlays />
      </Section>

      <Section title="아이콘">
        <ul className="text-caption text-fg-subtle flex flex-wrap gap-6">
          <li className="text-fg flex flex-col items-center gap-1 font-bold">
            <House size={24} weight="fill" aria-hidden />
            이야기
          </li>
          <li className="flex flex-col items-center gap-1 font-bold">
            <Sword size={24} aria-hidden />
            함께
          </li>
          <li className="flex flex-col items-center gap-1 font-bold">
            <PencilSimpleLine size={24} aria-hidden />
            쓰기
          </li>
          <li className="flex flex-col items-center gap-1 font-bold">
            <User size={24} aria-hidden />
            기록
          </li>
        </ul>
      </Section>

      <Section title="상대 시각">
        <ul className="text-label text-fg-subtle flex flex-wrap gap-4">
          {TIMES.map((time) => (
            <li key={time}>{formatRelativeTime(time, NOW)}</li>
          ))}
        </ul>
      </Section>

      <Section title="감정">
        <ul className="flex flex-col gap-4">
          {EMOTIONS.map((emotion) => (
            <li
              key={emotion}
              data-testid={`emotion-${emotion}`}
              className="flex items-center gap-3"
            >
              <MonsterThumb emotion={emotion} stage="hurt" />
              <div className="flex min-w-0 flex-1 flex-col gap-2">
                <EmotionTag emotion={emotion} className="self-start" />
                <HpBar emotion={emotion} hp={62} maxHp={100} />
              </div>
            </li>
          ))}
        </ul>
        <div data-testid="emotion-fallback" className="bg-line h-1.5 w-full">
          <div className="bg-emotion h-full w-1/2" />
        </div>
        <div
          data-emotion="UNKNOWN"
          data-testid="emotion-unknown"
          className="bg-line h-1.5 w-full"
        >
          <div className="bg-emotion h-full w-1/2" />
        </div>
      </Section>

      <Section title="HP 막대의 끝">
        <div data-testid="catalog-hp-edges" className="flex flex-col gap-3">
          <HpBar emotion="IRRITATION" hp={0} maxHp={10000} size="lg" />
          <HpBar emotion="IRRITATION" hp={40} maxHp={10000} size="lg" />
          <HpBar emotion="LONELINESS" hp={9999} maxHp={10000} size="lg" />
          <HpBar emotion="LETHARGY" hp={100} maxHp={100} size="lg" />
        </div>
      </Section>

      <Section title="무대">
        <MonsterStage
          emotion="ANXIETY"
          className="rounded-surface flex flex-col items-center gap-3 p-5"
        >
          <div className="size-44">
            <MonsterSprite emotion="ANXIETY" stage="hurt" sizes="176px" />
          </div>
          <p className="text-label text-emotion-fg font-bold">
            불안 몬스터 · 상처 입음
          </p>
          <HpBar emotion="ANXIETY" hp={62} maxHp={100} size="lg" />
        </MonsterStage>
      </Section>
    </div>
  );
}
