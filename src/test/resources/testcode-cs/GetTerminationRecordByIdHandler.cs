using Elia.ConsumerCentricityPermission.Core.IDatabase.Logic;
using Elia.ConsumerCentricityPermission.Core.Domain;
using MediatR;
using Arc4u;

namespace ConsumerCentricityPermission.Core.Business.Handlers.TerminationRecordHandlers.Queries
{
    public class GetTerminationRecordByIdHandler : IRequestHandler<GetTerminationRecordByIdRequest, PermissionTerminationRecord?>
    {
        private readonly ITerminationRecordDL _terminationRecordDL;

        public GetTerminationRecordByIdHandler(ITerminationRecordDL terminationRecordDL)
        {
            _terminationRecordDL = terminationRecordDL;
        }

        public async Task<PermissionTerminationRecord?> Handle(GetTerminationRecordByIdRequest request, CancellationToken cancellationToken)
        {
            PermissionTerminationRecord? result =
                await _terminationRecordDL.GetByIdAsync(request.TerminationRecordId, new Graph<PermissionTerminationRecord>(), cancellationToken)
                .ConfigureAwait(false);

            return result;
        }
    }

    public class GetTerminationRecordByIdRequest : IRequest<PermissionTerminationRecord?>
    {
        public Guid TerminationRecordId { get; set; }
        public GetTerminationRecordByIdRequest(Guid terminationRecordId)
        {
            TerminationRecordId = terminationRecordId;
        }
    }
}