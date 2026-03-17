package jobs

import "time"

const DefaultMaxIssueFixAttempts = 20

type JobSpec struct {
	TaskInput           string            `json:"taskInput"`
	AutoCommit          bool              `json:"autoCommit"`
	AutoCompress        bool              `json:"autoCompress"`
	PlannerModel        string            `json:"plannerModel"`
	ScanModel           *string           `json:"scanModel"`
	CodeModel           *string           `json:"codeModel"`
	PreScan             bool              `json:"preScan"`
	Tags                map[string]string `json:"tags"`
	SourceBranch        *string           `json:"sourceBranch"`
	TargetBranch        *string           `json:"targetBranch"`
	ReasoningLevel      *string           `json:"reasoningLevel"`
	ReasoningLevelCode  *string           `json:"reasoningLevelCode"`
	Temperature         *float64          `json:"temperature"`
	TemperatureCode     *float64          `json:"temperatureCode"`
	SkipVerification    bool              `json:"skipVerification"`
	MaxIssueFixAttempts *int              `json:"maxIssueFixAttempts"`
}

func (j JobSpec) RedactedTags() map[string]string {
	result := make(map[string]string, len(j.Tags))
	for key, value := range j.Tags {
		if key == "github_token" {
			result[key] = "[REDACTED]"
			continue
		}
		result[key] = value
	}
	return result
}

func MinimalJobSpec(taskInput string, plannerModel string) JobSpec {
	maxAttempts := DefaultMaxIssueFixAttempts
	return JobSpec{
		TaskInput:           taskInput,
		AutoCommit:          true,
		AutoCompress:        true,
		PlannerModel:        plannerModel,
		Tags:                map[string]string{},
		SkipVerification:    false,
		MaxIssueFixAttempts: &maxAttempts,
	}
}

type JobStatus struct {
	JobID           string            `json:"jobId"`
	State           string            `json:"state"`
	StartTime       int64             `json:"startTime"`
	EndTime         int64             `json:"endTime"`
	ProgressPercent int               `json:"progressPercent"`
	Result          any               `json:"result"`
	Error           *string           `json:"error"`
	Metadata        map[string]string `json:"metadata"`
}

func QueuedStatus(jobID string) JobStatus {
	return JobStatus{
		JobID:           jobID,
		State:           "QUEUED",
		StartTime:       time.Now().UnixMilli(),
		EndTime:         0,
		ProgressPercent: 0,
		Result:          nil,
		Error:           nil,
		Metadata:        map[string]string{},
	}
}

type JobEvent struct {
	Seq       int64  `json:"seq"`
	Timestamp int64  `json:"timestamp"`
	Type      string `json:"type"`
	Data      any    `json:"data"`
}

func NewEvent(eventType string, data any) JobEvent {
	return JobEvent{
		Seq:       -1,
		Timestamp: time.Now().UnixMilli(),
		Type:      eventType,
		Data:      data,
	}
}

type JobCreateResult struct {
	JobID    string
	IsNewJob bool
}

type JobSummary struct {
	JobID     string
	State     string
	StartTime int64
	EndTime   int64
	Mode      string
	TaskInput string
}
