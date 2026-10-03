export {
  attackOptimistically,
  refreshAfterAttack,
} from "./api/optimistic-cache";
export { useCommentLikeMutation } from "./api/use-comment-like-mutation";
export { usePostLikeMutation } from "./api/use-post-like-mutation";
export {
  ATTACK_DAMAGE,
  applyOptimisticAttack,
  optimisticMonster,
} from "./model/optimistic-hp";
export type { AttackKind, AttackOptions } from "./model/optimistic-hp";
export { CommentLikeButton } from "./ui/comment-like-button";
export { PostLikeButton } from "./ui/post-like-button";
