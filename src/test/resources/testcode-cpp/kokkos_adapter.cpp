#include "kokkos_adapter.hpp"

namespace kokkos_like {

template <class TeamMember>
KOKKOS_INLINE_FUNCTION
TaskTeamMemberAdapter<TeamMember>::TaskTeamMemberAdapter(TeamMember const& member)
    : member_(member) {}

template <class TeamMember>
KOKKOS_INLINE_FUNCTION
void TaskTeamMemberAdapter<TeamMember>::operator()(int i) const {
  (void)i;
}

// Explicit instantiation for a concrete type to ensure the parser sees templates in a realistic way.
struct DummyTeamMember {};
template struct TaskTeamMemberAdapter<DummyTeamMember>;

} // namespace kokkos_like
