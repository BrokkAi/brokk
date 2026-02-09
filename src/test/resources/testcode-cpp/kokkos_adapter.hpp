#pragma once

#ifndef KOKKOS_INLINE_FUNCTION
#define KOKKOS_INLINE_FUNCTION inline
#endif

namespace kokkos_like {

template <class TeamMember>
struct TaskTeamMemberAdapter {
  // Declaration inside the class body (may be captured via field_declaration + function_declarator)
  KOKKOS_INLINE_FUNCTION
  TaskTeamMemberAdapter(TeamMember const& member);

  KOKKOS_INLINE_FUNCTION
  void operator()(int i) const;

  TeamMember member_;
};

} // namespace kokkos_like
