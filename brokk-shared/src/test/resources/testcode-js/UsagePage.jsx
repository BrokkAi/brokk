import React, { useState, useEffect, useRef } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "../components/ui/card";
import {
  Activity,
  Clock,
  Zap,
  BarChart,
  Loader2,
  AlertCircle,
  Info,
} from "lucide-react";
import { useAlert } from "../contexts/AlertContext";
import { useUser } from "../contexts/UserContext";
import { Button } from "../components/ui/button";
import { format, parseISO } from "date-fns";
import {
  ResponsiveContainer,
  BarChart as RechartsBarChart,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  Bar,
} from "recharts";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../components/ui/select";
import {
  TooltipProvider,
  HoverTooltip,
  TooltipContent,
  TooltipTrigger,
} from "../components/ui/tooltip";

const UsagePage = () => {
  const {
    usageData,
    usageOverview,
    isUsageLoading,
    usageError,
    isUsageOverviewLoading,
    usageOverviewError,
    usagePage,
    hasMoreUsageData,
    fetchUsagePageData,
    fetchUsageOverviewData,
    hasFetchedUsage,
  } = useUser();

  const usageFetchAttempted = useRef(false);
  const [selectedTimePeriod, setSelectedTimePeriod] = useState("last30days");
  const [usageFilter, setUsageFilter] = useState("all"); // "all" or "failed"
  const lastFetchedFilter = useRef("all"); // Track last filter used for fetching

  // Function to generate dummy chart data
  const generateChartData = (period) => {
    if (!usageOverview || !usageOverview.period_aggregation) return [];

    const periodData = usageOverview.period_aggregation[period];
    if (!periodData || !periodData.model_breakdown) return [];

    // Convert aggregated data to chart format
    return Object.entries(periodData.model_breakdown).map(
      ([modelName, stats]) => {
        const nameParts = modelName.split("/");
        const shortModelName = nameParts[nameParts.length - 1];
        return {
          modelName: shortModelName,
          successes: stats.successes,
          failures: stats.failures,
          totalCost: stats.total_cost,
        };
      }
    );
  };

  const [chartData, setChartData] = useState(
    generateChartData(selectedTimePeriod)
  );

  useEffect(() => {
    // Update chart data when usage overview or time period changes
    setChartData(generateChartData(selectedTimePeriod));
  }, [selectedTimePeriod, usageOverview]);

  useEffect(() => {
    // Refetch usage data when usageFilter changes
    if (hasFetchedUsage && lastFetchedFilter.current !== usageFilter) {
      fetchUsagePageData(1, false, { usage_filter: usageFilter });
      lastFetchedFilter.current = usageFilter;
    }
  }, [usageFilter, hasFetchedUsage, fetchUsagePageData]);

  useEffect(() => {
    if (process.env.NODE_ENV === "development" && usageFetchAttempted.current) {
      return;
    }

    if (!hasFetchedUsage) {
      fetchUsageOverviewData({});
      fetchUsagePageData(1, false, { usage_filter: usageFilter });
    }

    if (process.env.NODE_ENV === "development") {
      usageFetchAttempted.current = true;
    }

    return () => {
      if (process.env.NODE_ENV === "development") {
        // usageFetchAttempted.current = false;
      }
    };
  }, [hasFetchedUsage, fetchUsageOverviewData, fetchUsagePageData]);

  const handlePrevious = () => {
    if (usagePage > 1) {
      fetchUsagePageData(usagePage - 1, false, { usage_filter: usageFilter });
    }
  };

  const handleNext = () => {
    if (hasMoreUsageData) {
      fetchUsagePageData(usagePage + 1, false, { usage_filter: usageFilter });
    }
  };

  const formatCurrency = (amount) => {
    const num = parseFloat(amount);
    if (isNaN(num)) {
      return "$0.00";
    }
    return `$${num.toFixed(2)}`;
  };

  const CustomTooltip = ({ active, payload, label }) => {
    if (active && payload && payload.length) {
      const data = payload[0].payload; // Full data for the hovered model
      const hoveredBarName = payload[0].name; // "Successes" or "Failures"
      const hoveredValue = payload[0].value;

      return (
        <div className="bg-background border border-border shadow-lg p-3 rounded-md">
          <p className="font-semibold text-foreground">{`${data.modelName}`}</p>
          <p className="text-sm text-muted-foreground">
            {hoveredBarName}: {hoveredValue}
          </p>
          <p className="text-sm text-muted-foreground">
            Total Requests: {data.successes + data.failures}
          </p>
          <p className="text-sm text-foreground mt-1">
            Aggregated Cost: {formatCurrency(data.totalCost)}
          </p>
        </div>
      );
    }
    return null;
  };

  const CustomXAxisTick = ({
    x,
    y,
    payload,
    angle = -45,
    passedTextAnchor = "end",
  }) => {
    const { value } = payload;
    const MAX_CHARS_PER_LINE = 12;
    const MAX_LINES = 3;
    const LINE_HEIGHT = 12;
    const FONT_SIZE = "10px";

    const words = value
      .replace(/[-_]/g, " ")
      .split(" ")
      .filter((w) => w.length > 0);
    let lines = [];
    let currentLine = "";

    // Attempt wrapping only if the string is potentially long enough to benefit
    if (value.length > MAX_CHARS_PER_LINE * 0.7) {
      for (const word of words) {
        if (currentLine.length === 0) {
          currentLine = word;
        } else if ((currentLine + " " + word).length <= MAX_CHARS_PER_LINE) {
          currentLine += " " + word;
        } else {
          if (currentLine.length > 0) lines.push(currentLine);
          currentLine = word;
        }
      }
      if (currentLine.length > 0) lines.push(currentLine);
    }

    // If no wrapping occurred (e.g., short string or single long word), use the original value
    if (lines.length === 0 && value && value.length > 0) {
      lines.push(value);
    } else if (!value || value.length === 0) {
      return null; // Handle empty value case
    }

    // Trim lines, filter empty ones, and apply MAX_LINES truncation with ellipsis
    let effectiveLines = lines.map((l) => l.trim()).filter((l) => l.length > 0);
    if (effectiveLines.length > MAX_LINES) {
      const lastVisibleLineIndex = MAX_LINES - 1;
      let lastLineText = effectiveLines[lastVisibleLineIndex];
      // Add ellipsis, ensuring it doesn't make the line too long or awkward
      if (lastLineText.length > 3) {
        lastLineText =
          lastLineText.substring(
            0,
            Math.min(lastLineText.length, MAX_CHARS_PER_LINE - 3)
          ) + "...";
      } else {
        lastLineText = "...";
      }
      effectiveLines = effectiveLines
        .slice(0, lastVisibleLineIndex)
        .concat(lastLineText);
    }

    const isMultiLine = effectiveLines.length > 1;
    const currentTextAnchor = isMultiLine ? "end" : passedTextAnchor;

    // Add a vertical offset to push the labels down from the bars
    const yOffset = 5;

    return (
      <g transform={`translate(${x},${y + yOffset}) rotate(${angle})`}>
        {effectiveLines.map((lineText, index) => (
          <text
            key={index}
            x={0}
            y={index * LINE_HEIGHT}
            textAnchor={currentTextAnchor}
            fill="hsl(var(--popover-foreground))"
            style={{ fontSize: FONT_SIZE, dominantBaseline: "central" }}
          >
            {lineText}
          </text>
        ))}
      </g>
    );
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Usage Analytics</h1>
        <p className="text-muted-foreground mt-2">
          Monitor your LLM usage and performance metrics
        </p>
      </div>
      {(usageOverviewError || usageError) && (
        <div className="flex items-center text-red-500 p-2 mt-2 bg-red-100 dark:bg-red-900 rounded">
          <AlertCircle className="h-4 w-4 mr-2 flex-shrink-0" />
          <p className="text-sm">{usageOverviewError || usageError}</p>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">
              Total Requests
            </CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {isUsageOverviewLoading ? (
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            ) : (
              <div className="text-2xl font-bold">
                {usageOverview?.total_requests ?? 0}
              </div>
            )}
            <p className="text-xs text-muted-foreground mt-1">Total requests</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Success Rate</CardTitle>
            <Zap className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {isUsageOverviewLoading ? (
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            ) : (
              <div className="text-2xl font-bold">
                {(usageOverview?.success_rate ?? 0).toFixed(1)}%
              </div>
            )}
            <p className="text-xs text-muted-foreground mt-1">All requests</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Total Spend</CardTitle>
            <BarChart className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {isUsageOverviewLoading ? (
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            ) : (
              <div className="text-2xl font-bold">
                {formatCurrency(usageOverview?.total_cost)}
              </div>
            )}
            <p className="text-xs text-muted-foreground mt-1">Total spend</p>
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex justify-between items-center">
              <div>
                <CardTitle>Usage Over Time</CardTitle>
                <CardDescription>
                  Model performance and cost analysis
                </CardDescription>
              </div>
              <Select
                value={selectedTimePeriod}
                onValueChange={setSelectedTimePeriod}
              >
                <SelectTrigger className="w-[180px]">
                  <SelectValue placeholder="Select period" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="last30days">Last 30 Days</SelectItem>
                  <SelectItem value="last7days">Last 7 Days</SelectItem>
                  <SelectItem value="last24hours">Last 24 Hours</SelectItem>
                  <SelectItem value="last1hour">Last Hour</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </CardHeader>
          <CardContent className="p-0 h-[325px] pt-2">
            {isUsageOverviewLoading ? (
              <div className="h-full w-full bg-muted/20 rounded-md flex items-center justify-center">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                <span className="ml-2 text-muted-foreground">
                  Loading chart data...
                </span>
              </div>
            ) : usageOverviewError ? (
              <div className="h-full w-full bg-muted/20 rounded-md flex items-center justify-center text-center">
                <AlertCircle className="h-5 w-5 text-red-500 mb-2 mx-auto" />
                <p className="text-red-500">
                  Failed to load chart data: <br /> {usageOverviewError}
                </p>
              </div>
            ) : chartData.length === 0 ? (
              <div className="h-full w-full bg-muted/20 rounded-md flex items-center justify-center text-center">
                <Info className="h-5 w-5 text-muted-foreground mr-2" />
                <p className="text-muted-foreground">
                  No usage data available for the selected period.
                </p>
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <RechartsBarChart
                  data={chartData}
                  margin={{ top: 5, right: 20, left: 0, bottom: 0 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 3"
                    stroke="hsl(var(--border))"
                  />
                  <XAxis
                    dataKey="modelName"
                    height={80}
                    interval={0}
                    tick={
                      <CustomXAxisTick angle={-45} passedTextAnchor="end" />
                    }
                  />
                  <YAxis
                    label={{
                      value: "Total Requests",
                      angle: -90,
                      position: "insideLeft",
                      offset: -10,
                      style: {
                        textAnchor: "middle",
                        fontSize: "12px",
                        fill: "hsl(var(--muted-foreground))",
                      },
                    }}
                    tick={{
                      fontSize: "10px",
                      fill: "hsl(var(--popover-foreground))",
                    }}
                  />
                  <Tooltip
                    content={<CustomTooltip />}
                    cursor={{ fill: "transparent" }}
                  />
                  <Bar
                    dataKey="failures"
                    stackId="a"
                    fill="#888"
                    name="Failures"
                    activeBar={{ fillOpacity: 0.8 }}
                  />
                  <Bar
                    dataKey="successes"
                    stackId="a"
                    fill="hsl(var(--primary))"
                    name="Successes"
                    activeBar={{ fillOpacity: 0.8 }}
                  />
                </RechartsBarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        <Card className="lg:col-span-1">
          <CardHeader>
            <div className="flex justify-between items-center">
              <div>
                <CardTitle>LLM Usage</CardTitle>
                <CardDescription>Recent API calls</CardDescription>
              </div>
              <Select value={usageFilter} onValueChange={setUsageFilter}>
                <SelectTrigger className="w-[160px]">
                  <SelectValue placeholder="Filter usage" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Usage</SelectItem>
                  <SelectItem value="failed">Failures Only</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </CardHeader>
          <CardContent>
            <TooltipProvider delayDuration={300}>
              {isUsageLoading && !hasFetchedUsage ? (
                <div className="h-[300px] w-full bg-muted/20 rounded-md flex items-center justify-center">
                  <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  <span className="ml-2 text-muted-foreground">
                    Loading usage data...
                  </span>
                </div>
              ) : usageError ? (
                <div className="h-[300px] w-full bg-muted/20 rounded-md flex items-center justify-center text-center">
                  <AlertCircle className="h-5 w-5 text-red-500 mb-2 mx-auto" />
                  <p className="text-red-500">
                    Failed to load usage data: <br />
                    {usageError}
                  </p>
                </div>
              ) : usageData.length === 0 ? (
                <div className="h-[300px] w-full bg-muted/20 rounded-md flex items-center justify-center text-center">
                  <Info className="h-5 w-5 text-muted-foreground mr-2" />
                  <p className="text-muted-foreground">
                    No recent usage data available.
                  </p>
                </div>
              ) : (
                <div className="flex flex-col h-[300px] max-h-[500px]">
                  <div className="flex-grow overflow-y-auto space-y-4 pr-2">
                    {usageData.map((event) => (
                      <div
                        key={event.id}
                        className="flex items-center justify-between border-b pb-3 pt-1"
                      >
                        <div className="space-y-1 flex-grow mr-4 min-w-0">
                          <p className="text-sm font-medium leading-none truncate">
                            {format(parseISO(event.created_at), "Pp")}
                          </p>
                          <div className="flex items-center">
                            <p className="text-sm text-muted-foreground truncate">
                              Model: {event.model}
                            </p>
                            {event.message && (
                              <HoverTooltip>
                                <TooltipTrigger className="inline-flex items-center justify-center ml-2">
                                  <Info className="h-4 w-4 text-primary cursor-pointer flex-shrink-0" />
                                </TooltipTrigger>
                                <TooltipContent
                                  side="top"
                                  className="max-w-xs break-words"
                                >
                                  <p>{event.message}</p>
                                </TooltipContent>
                              </HoverTooltip>
                            )}
                          </div>
                        </div>
                        <div className="text-right space-y-1 flex-shrink-0">
                          <p className="text-sm font-medium">
                            Cost: {formatCurrency(event.cost)}
                          </p>
                          <p
                            className={`text-sm ${
                              event.success ? "text-green-600" : "text-red-600"
                            }`}
                          >
                            {event.success ? "Success" : "Failed"}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="flex-shrink-0 flex justify-between py-3 border-t border-border pr-2">
                    <Button
                      onClick={handlePrevious}
                      disabled={usagePage === 1 || isUsageLoading}
                      className="px-4 py-2 bg-primary text-white rounded-md disabled:bg-muted disabled:cursor-not-allowed"
                    >
                      {isUsageLoading && usagePage > 1 ? (
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      ) : null}
                      Previous
                    </Button>
                    <Button
                      onClick={handleNext}
                      disabled={!hasMoreUsageData || isUsageLoading}
                      className="px-4 py-2 bg-primary text-white rounded-md disabled:bg-muted disabled:cursor-not-allowed"
                    >
                      {isUsageLoading && hasMoreUsageData ? (
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      ) : null}
                      Next
                    </Button>
                  </div>
                </div>
              )}
            </TooltipProvider>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default UsagePage;
